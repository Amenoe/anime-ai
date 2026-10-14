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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** M2 管理入口：索引构建和纯检索与生成链路分离。 */
@RestController
@RequestMapping("/internal")
@ConditionalOnExpression("'${ai.embedding.api-key:}' != ''")
public class KnowledgeController {
    private static final String TOKEN_HEADER = "X-Internal-Token";
    private final KnowledgeService knowledge;
    private final CorpusLoader corpus;
    private final AiProperties props;

    public KnowledgeController(KnowledgeService knowledge, CorpusLoader corpus, AiProperties props) {
        this.knowledge = knowledge;
        this.corpus = corpus;
        this.props = props;
    }

    @PostMapping("/retrieve")
    public RetrieveResponse retrieve(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody RetrieveRequest request) {
        assertToken(token);
        int topK = request.topK() == null ? props.qdrant().topK() : request.topK();
        List<Map<String, Object>> hits = knowledge.retrieve(request.query(), topK, request.filters())
                .stream().map(hit -> Map.<String, Object>of(
                        "subject_id", hit.subjectId(), "score", hit.score(), "text", hit.text(), "payload", hit.payload()))
                .toList();
        return new RetrieveResponse(hits);
    }

    @PostMapping("/knowledge/ingest")
    public Map<String, Object> ingest(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody IngestRequest request) {
        assertToken(token);
        boolean suppliedDocuments = request.documents() != null && !request.documents().isEmpty();
        boolean useBangumi = "bangumi".equalsIgnoreCase(request.source())
                || (!StringUtils.hasText(request.source()) && !StringUtils.hasText(props.corpus().jdbcUrl()));
        String source = suppliedDocuments ? "request" : useBangumi ? "bangumi" : "mysql";
        List<KnowledgeDocument> documents = suppliedDocuments
                ? request.documents()
                : useBangumi ? corpus.loadFromBangumi(request.limit()) : corpus.loadFromMySql(request.limit());
        return Map.of("upserted", knowledge.ingest(documents), "loaded", documents.size(), "source", source);
    }

    private void assertToken(String token) {
        if (StringUtils.hasText(props.internalToken()) && !props.internalToken().equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部令牌无效");
        }
    }

    public record RetrieveRequest(@NotBlank String query, Integer topK, Map<String, Object> filters) {}

    public record RetrieveResponse(List<Map<String, Object>> hits) {}

    public record IngestRequest(Integer limit, String source, List<KnowledgeDocument> documents) {}
}
