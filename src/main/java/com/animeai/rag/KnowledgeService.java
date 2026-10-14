package com.animeai.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import com.animeai.config.AiProperties;

import dev.langchain4j.data.segment.TextSegment;
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
    private final EmbeddingModel embeddingModel;
    private final QdrantClient qdrant;
    private final AiProperties props;

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
     * 对查询文本做向量检索。
     *
     * <p>查询必须使用和入库相同的 embedding 模型，否则向量空间不一致，分数没有意义。
     * {@code topK} 被限制在 1 到 50，避免调用方一次拉取过多上下文；年份、评分和公共标签
     * 等结构化条件会由 {@link QdrantClient} 转为 Qdrant 的 pre-filter，在召回前过滤。
     */
    public List<KnowledgeHit> retrieve(String query, int topK, Map<String, Object> filters) {
        if (query == null || query.isBlank()) return List.of();
        float[] vector = embeddingModel.embed(query).content().vector();
        return qdrant.search(vector, Math.max(1, Math.min(topK, 50)), filters);
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
