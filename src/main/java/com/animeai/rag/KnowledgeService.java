package com.animeai.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;

import com.animeai.config.AiProperties;

import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * M2 的知识库服务：负责把文本转换成向量、写入 Qdrant，并执行纯向量检索。
 *
 * <p>一次完整的 RAG 数据流是：
 * <ol>
 *   <li>{@link KnowledgeDocument} 保存原始文本和业务元数据；</li>
 *   <li>{@link EmbeddingModel} 把文本转换成 1024 维 BGE-M3 向量；</li>
 *   <li>{@link QdrantClient} 将向量和 payload 写入 {@code anime_knowledge} 集合；</li>
 *   <li>查询文本也经过同一个 embedding 模型，再让 Qdrant 按 Cosine 相似度返回 top-k。</li>
 * </ol>
 *
 * <p>这里不调用聊天模型，也不拼接 prompt。这样 {@code /internal/retrieve} 可以单独用于
 * M3 的 Hit Rate、MRR 等离线评估，聊天生成只是它的一个上层使用者。
 *
 * <p>M2 的一部番剧简介就是一个完整语义单元，因此不在这里做 chunking。长文本的切分留给
 * 后续字幕和群聊语料阶段。
 */
@Service
@ConditionalOnBean(EmbeddingModel.class)
public class KnowledgeService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final EmbeddingModel embeddingModel;
    private final QdrantClient qdrant;
    private final AiProperties props;

    /*
     * 检索策略名。**做成请求参数而不是编译期常量**：这三条的取舍必须用同一份 golden set
     * 对照测量来决定（eval/run_eval.py --strategy），靠直觉选一定选错 —— 本仓实测过
     * 「看起来该有用的改动」反而让指标下降。
     */
    public static final String STRATEGY_VECTOR = "vector";
    public static final String STRATEGY_HYBRID = "hybrid";
    public static final String STRATEGY_RRF = "rrf";
    public static final String STRATEGY_HYBRID_RRF = "hybrid-rrf";

    /**
     * 默认策略。取值由 {@code eval/run_eval.py} 的对照实验定，不是拍脑袋选的：
     *
     * <pre>
     * 策略          HitRate@10   MRR      concept      filter MRR
     * vector         91.7%      0.6285   5/6·0.4931   0.2917
     * hybrid        100.0%      0.8056   6/6·0.8056   0.4167   ← 默认
     * rrf            75.0%      0.5397   4/6·0.4405   0.1667
     * hybrid-rrf    100.0%      0.7341   6/6·0.6349   0.5000
     * </pre>
     *
     * ⚠️ **纯 RRF 比不做还差**（91.7% → 75.0%）。原因值得记住：RRF 只吃排名、丢掉
     * 相似度，相当于给每个子查询**同等投票权**；而「家族」这类与语料不匹配的词，
     * 它那一路的 top10 全是噪音（亚当斯一家/辛普森一家），融合时这些噪音被抬到了前面，
     * 真正的目标反被稀释。当时对「多词查询被强特征词主导」的诊断是对的，
     * 但「让每个词都发声」这个药方是错的 —— 正解是保留整体语义、把字面命中信号加回来。
     * 留在这里是因为它是重要的负结果：语料或模型变了值得复测，但不要默认启用。
     */
    public static final String DEFAULT_STRATEGY = STRATEGY_HYBRID;

    /** 混合策略先多召回几倍候选再重排；否则真正该赢的点可能压根不在原始 topK 里。 */
    private static final int HYBRID_CANDIDATES = 3;
    /**
     * 字面命中加分的上限。实测向量分差多在 0.01~0.05 量级，0.08 足以翻转
     * 「只差一点但标签明确命中」的情况，又不至于让纯标签匹配的无关作品反超语义正确的点。
     */
    private static final double LABEL_BOOST = 0.08;
    /** RRF 平滑常数，取文献常用值 60。 */
    private static final int RRF_K = 60;

    public KnowledgeService(EmbeddingModel embeddingModel, QdrantClient qdrant, AiProperties props) {
        this.embeddingModel = embeddingModel;
        this.qdrant = qdrant;
        this.props = props;
    }

    /**
     * 确保 Qdrant 集合和 payload 索引存在。
     *
     * <p>这个操作是幂等的：集合已经存在时，Qdrant 返回 409，客户端会忽略；因此每次导入
     * 前调用一次即可，不需要手工先创建 collection。向量维度必须和 embedding 模型一致，
     * 当前配置为 BGE-M3 的 1024 维。
     */
    public void prepare() {
        qdrant.ensureCollection(props.embedding().dimensions());
    }

    /**
     * 批量导入知识文档。
     *
     * <p>导入分两步：先从 Qdrant 读取已有的 {@code content_hash}，再只对新增或内容变化的
     * 文档调用 embedding API。这样重复执行导入不会重复消耗 embedding 配额；Qdrant 使用
     * {@code subjectId} 作为 point id，upsert 会覆盖同一部作品的旧向量。
     *
     * <p>embedding 按批调用而不是逐条调用，减少网络往返。批量上限同时受配置和 128 的保护，
     * 避免一次请求过大导致供应商限流或请求体过大。
     *
     * @return 实际重新计算并写入 Qdrant 的文档数量；未变化的文档不会计入
     */
    public int ingest(List<KnowledgeDocument> documents) {
        if (documents == null || documents.isEmpty()) return 0;
        prepare();
        int batchSize = Math.max(1, Math.min(props.corpus().batchSize(), 128));
        Map<Long, String> oldHashes = new LinkedHashMap<>();
        // 先批量查询已有指纹，避免为了判断增量而逐条请求 Qdrant。
        for (int start = 0; start < documents.size(); start += batchSize) {
            List<KnowledgeDocument> batch = documents.subList(start, Math.min(start + batchSize, documents.size()));
            List<Long> ids = batch.stream().map(KnowledgeDocument::subjectId).toList();
            oldHashes.putAll(qdrant.existingHashes(ids));
        }
        int embedded = 0;
        for (int start = 0; start < documents.size(); start += batchSize) {
            // 内容 hash 未变化的条目复用已有向量；变化条目才重新 embedding。
            List<KnowledgeDocument> batch = documents.subList(start, Math.min(start + batchSize, documents.size())).stream()
                    .filter(document -> !hash(document.text()).equals(oldHashes.get(document.subjectId())))
                    .toList();
            if (batch.isEmpty()) continue;
            List<TextSegment> segments = batch.stream().map(document -> TextSegment.from(document.text())).toList();
            // embedAll 返回值与 segments 保持顺序一致，下面按下标把向量和 subject_id 配对。
            List<dev.langchain4j.data.embedding.Embedding> vectors = embeddingModel.embedAll(segments).content();
            List<QdrantClient.KnowledgePoint> points = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                KnowledgeDocument document = batch.get(i);
                Map<String, Object> payload = new HashMap<>(document.payload());
                payload.put("subject_id", document.subjectId());
                payload.put("text", document.text());
                // content_hash 放进 payload，下一次导入时可以直接从 Qdrant 判断是否需要更新。
                payload.put("content_hash", hash(document.text()));
                points.add(new QdrantClient.KnowledgePoint(document.subjectId(), vectors.get(i).vector(), payload));
            }
            qdrant.upsert(points);
            embedded += points.size();
        }
        return embedded;
    }

    /**
     * 对查询文本做向量检索（默认策略 {@link #STRATEGY_VECTOR}）。
     *
     * <p>查询必须使用和入库相同的 embedding 模型，否则向量空间不一致，分数没有意义。
     * {@code topK} 被限制在 1 到 50，避免调用方一次拉取过多上下文；年份、评分和公共标签
     * 等结构化条件会由 {@link QdrantClient} 转为 Qdrant 的 pre-filter，在召回前过滤。
     */
    public List<KnowledgeHit> retrieve(String query, int topK, Map<String, Object> filters) {
        return retrieve(query, topK, filters, configuredStrategy());
    }

    /**
     * 默认策略：**配置优先，代码兜底**。
     *
     * <p>做成配置是为了不用改代码就能切换/回滚（见 application.yml 的 ai.retrieval）。
     * 但配置写错时不能把系统搞挂，所以非法值一律回落到 {@link #DEFAULT_STRATEGY}。
     */
    private String configuredStrategy() {
        String configured = props.retrieval() == null ? null : props.retrieval().strategy();
        if (configured == null || configured.isBlank()) return DEFAULT_STRATEGY;
        String normalized = configured.trim().toLowerCase();
        return switch (normalized) {
            case STRATEGY_VECTOR, STRATEGY_HYBRID, STRATEGY_RRF, STRATEGY_HYBRID_RRF -> normalized;
            default -> {
                // 配置写错不该让检索失效，但必须留痕 —— 否则「配了却没生效」会静默很久
                log.warn("ai.retrieval.strategy 取值非法（{}），回落到默认 {}", configured, DEFAULT_STRATEGY);
                yield DEFAULT_STRATEGY;
            }
        };
    }

    /**
     * 按 subject_id 批量取题材标签（{@code meta_tags}），用于「按用户看过的番推断口味」。
     *
     * <p>只读 Qdrant 的 payload，不做向量检索，所以不走 {@code ai.retrieval.strategy}；
     * 但**遵守 {@code ai.retrieval.enabled} 这个总开关** —— 关掉检索时不该还有隐含的 Qdrant 调用。
     *
     * @return 查不到的就缺席（调用方自行兜底），不抛异常
     */
    public Map<Integer, List<String>> tagsOf(Collection<Integer> subjectIds) {
        if (subjectIds == null || subjectIds.isEmpty()) return Map.of();
        if (props.retrieval() != null && !props.retrieval().enabled()) return Map.of();
        List<Long> ids = subjectIds.stream()
                .filter(Objects::nonNull)
                .map(Integer::longValue)
                .distinct()
                .limit(24)
                .toList();
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        qdrant.metaTagsOf(ids).forEach((id, tags) -> result.put(id.intValue(), tags));
        return result;
    }

    /** 按指定策略检索，见类顶部 STRATEGY_* 常量的说明。 */
    public List<KnowledgeHit> retrieve(String query, int topK, Map<String, Object> filters, String strategy) {
        if (query == null || query.isBlank()) return List.of();
        int limit = Math.max(1, Math.min(topK, 50));
        // ⚠️ 「不传策略」必须等于「用默认策略」，不能退化成最原始的算法。
        // 这里曾写成 strategy == null ? "" : ...，于是空值落到 switch 的 default 分支
        // 变成 vector —— 表现为「默认策略改了但不生效」，而显式传 hybrid 却正常。
        String mode = strategy == null || strategy.isBlank()
                ? configuredStrategy()
                : strategy.trim().toLowerCase();
        return switch (mode) {
            case STRATEGY_RRF -> retrieveRrf(query, limit, filters);
            case STRATEGY_HYBRID -> retrieveHybrid(query, limit, filters);
            case STRATEGY_HYBRID_RRF -> retrieveHybridRrf(query, limit, filters);
            default -> qdrant.search(embed(query), limit, filters);
        };
    }

    /**
     * 向量召回 + **标签字面命中加分**。
     *
     * <p>为什么需要：`filter` 类查询（如「政治 阴谋 机甲」）能召回但 MRR 偏低 ——
     * 关键词其实就写在 payload 的 tags / meta_tags 里，却被稠密向量平均掉了，
     * 「字面明确命中」的作品反而排不到前面。这里把字面命中显式加回去。
     */
    private List<KnowledgeHit> retrieveHybrid(String query, int limit, Map<String, Object> filters) {
        List<String> terms = terms(query);
        return rerankByLabels(qdrant.search(embed(query), limit * HYBRID_CANDIDATES, filters), terms, limit);
    }

    /**
     * 查询分解 + RRF（Reciprocal Rank Fusion）融合。
     *
     * <p>为什么需要：实测「催泪 家族 治愈」召回不到 CLANNAD，而**去掉「家族」只用
     * 「催泪 治愈」立刻回到 rank=5** —— 多词查询里某个"强特征词"会把向量方向带偏、
     * 压掉其余信号。拆成子查询各自检索再融合，让每个词都保留自己的发言权。
     *
     * <p>RRF 只用**排名**不用原始分数，因此不受各子查询分数尺度不一致的影响，
     * 这正是它比「相似度加权平均」更稳的原因。
     */
    private List<KnowledgeHit> retrieveRrf(String query, int limit, Map<String, Object> filters) {
        return fuse(subQueries(query), limit, filters, null);
    }

    /** 标签加分 + RRF 叠加。 */
    private List<KnowledgeHit> retrieveHybridRrf(String query, int limit, Map<String, Object> filters) {
        return fuse(subQueries(query), limit, filters, terms(query));
    }

    /** 子查询 = 原查询（保留整体语义）+ 每个词（保留各自声音）。 */
    private static List<String> subQueries(String query) {
        List<String> out = new ArrayList<>();
        out.add(query.trim());
        for (String term : terms(query)) if (!term.equals(query.trim())) out.add(term);
        return out;
    }

    /**
     * 对多路召回结果做 RRF 融合并取前 limit 条。
     *
     * @param labelTerms 非 null 时，每路先按标签命中重排（混合 + RRF 叠加）
     */
    private List<KnowledgeHit> fuse(
            List<String> queries, int limit, Map<String, Object> filters, List<String> labelTerms) {
        Map<Long, Double> fused = new HashMap<>();
        Map<Long, KnowledgeHit> byId = new LinkedHashMap<>();
        for (String sub : queries) {
            List<KnowledgeHit> hits = labelTerms == null
                    ? qdrant.search(embed(sub), limit, filters)
                    : rerankByLabels(qdrant.search(embed(sub), limit * HYBRID_CANDIDATES, filters), labelTerms, limit);
            for (int rank = 0; rank < hits.size(); rank++) {
                KnowledgeHit hit = hits.get(rank);
                fused.merge(hit.subjectId(), 1.0 / (RRF_K + rank + 1), Double::sum);
                byId.putIfAbsent(hit.subjectId(), hit);
            }
        }
        return fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                // 用融合分替换原始相似度：调用方看到的 score 必须就是排序依据，否则没法解释排序
                .map(e -> {
                    KnowledgeHit hit = byId.get(e.getKey());
                    return new KnowledgeHit(hit.subjectId(), e.getValue(), hit.text(), hit.payload());
                })
                .toList();
    }

    /** 字面命中重排：命中词占比越高加分越多，但上限受 LABEL_BOOST 约束，不会压过语义。 */
    private static List<KnowledgeHit> rerankByLabels(
            List<KnowledgeHit> candidates, List<String> labelTerms, int limit) {
        if (labelTerms.isEmpty()) return candidates.stream().limit(limit).toList();
        List<KnowledgeHit> scored = new ArrayList<>(candidates.size());
        for (KnowledgeHit hit : candidates) {
            String haystack = labelText(hit.payload());
            long matched = labelTerms.stream().filter(t -> haystack.contains(t.toLowerCase())).count();
            scored.add(new KnowledgeHit(hit.subjectId(),
                    hit.score() + LABEL_BOOST * ((double) matched / labelTerms.size()),
                    hit.text(), hit.payload()));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.stream().limit(limit).toList();
    }

    /** 只拼名字与标签，**不含简介** —— 简介很长，命中几乎恒真，加分就没有区分度了。 */
    private static String labelText(Map<String, Object> payload) {
        StringBuilder out = new StringBuilder();
        for (String key : List.of("name", "name_cn")) {
            Object value = payload.get(key);
            if (value != null) out.append(String.valueOf(value).toLowerCase()).append(' ');
        }
        for (String key : List.of("tags", "meta_tags")) {
            if (payload.get(key) instanceof List<?> values) {
                for (Object value : values) {
                    if (value != null) out.append(String.valueOf(value).toLowerCase()).append(' ');
                }
            }
        }
        return out.toString();
    }

    /** 查询拆词：中文没有空格分词，只按显式分隔符拆；拆不动就退化成整体一路。 */
    private static List<String> terms(String query) {
        if (query == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : query.split("[\\s,，、/]+")) {
            String term = part.trim();
            if (!term.isEmpty() && !out.contains(term)) out.add(term);
        }
        return out;
    }

    private float[] embed(String text) {
        return embeddingModel.embed(text).content().vector();
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算语料指纹", e);
        }
    }
}
