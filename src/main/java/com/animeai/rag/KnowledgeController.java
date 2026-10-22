package com.animeai.rag;

import java.util.List;
import java.util.Map;

import com.animeai.config.AiProperties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** M2 管理入口：索引构建和纯检索与生成链路分离。 */
@RestController
@RequestMapping("/internal")
@ConditionalOnExpression("'${ai.embedding.api-key:}' != ''")
public class KnowledgeController {
    private final KnowledgeService knowledge;
    private final CorpusLoader corpus;
    private final AiProperties props;

    public KnowledgeController(KnowledgeService knowledge, CorpusLoader corpus, AiProperties props) {
        this.knowledge = knowledge;
        this.corpus = corpus;
        this.props = props;
    }

    @PostMapping("/retrieve")
    public RetrieveResponse retrieve(@Valid @RequestBody RetrieveRequest request) {
        int topK = request.topK() == null ? props.qdrant().topK() : request.topK();
        List<Map<String, Object>> hits = knowledge.retrieve(
                        request.query(), topK, request.filters(), request.strategy())
                .stream().map(hit -> Map.<String, Object>of(
                        "subject_id", hit.subjectId(), "score", hit.score(), "text", hit.text(), "payload", hit.payload()))
                .toList();
        return new RetrieveResponse(hits);
    }

    @PostMapping("/knowledge/ingest")
    public Map<String, Object> ingest(@RequestBody IngestRequest request) {
        boolean suppliedDocuments = request.documents() != null;
        // 传了空数组是**调用方的意图矛盾**：不传才会走默认语料源。
        // 不报错的话它会静默走默认源（甚至真的导入成功），与调用方预期不符。
        if (suppliedDocuments && request.documents().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "documents 为空数组；不传该字段才会走默认语料源（bangumi/mysql）");
        }
        boolean useBangumi = "bangumi".equalsIgnoreCase(request.source())
                || (!StringUtils.hasText(request.source()) && !StringUtils.hasText(props.corpus().jdbcUrl()));
        String source = suppliedDocuments ? "request" : useBangumi ? "bangumi" : "mysql";
        List<KnowledgeDocument> documents = suppliedDocuments
                ? request.documents()
                : useBangumi ? corpus.loadFromBangumi(request.limit()) : corpus.loadFromMySql(request.limit());
        /*
         * 0 条**不该被当成一次成功的导入**。
         * 本地 anime 表为空、jdbc-url 配了但库是空的、Bangumi 不可达，都会走到这里，
         * 而它返回的 upserted=0 与「幂等跳过」长得**一模一样**（区别只在 loaded=0）——
         * 实测踩过：.env.example 里那行 AI_CORPUS_JDBC_URL 模板一旦被取消注释，
         * 而 anime 表是空的，导入就“成功”地什么也没导。
         */
        if (documents.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "语料源 " + source + " 返回 0 条，未导入任何数据；请检查 ai.corpus 配置或语料源是否可达");
        }
        return Map.of("upserted", knowledge.ingest(documents), "loaded", documents.size(), "source", source);
    }

    /**
     * @param strategy 检索策略，**留空即 vector**（向后兼容）。可选 vector / hybrid / rrf /
     *                 hybrid-rrf。做成参数是为了能在同一份语料上跑对照实验，
     *                 而不是改代码重启逐次试。
     */
    public record RetrieveRequest(
            @NotBlank String query, Integer topK, Map<String, Object> filters, String strategy) {}

    public record RetrieveResponse(List<Map<String, Object>> hits) {}

    public record IngestRequest(Integer limit, String source, List<KnowledgeDocument> documents) {}
}
