package com.animeai.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.animeai.config.AiProperties;
import com.animeai.support.Json;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Qdrant REST 客户端。使用 REST 避免把 beta 客户端 API 绑定到服务代码。 */
@Component
public class QdrantClient {
    private final RestClient client;
    private final String collection;

    public QdrantClient(AiProperties props) {
        this.client = RestClient.builder().baseUrl(props.qdrant().baseUrl().replaceAll("/+$", "")).build();
        this.collection = props.qdrant().collection();
    }

    public void ensureCollection(int dimensions) {
        String body = "{\"vectors\":{\"size\":" + dimensions + ",\"distance\":\"Cosine\"}}";
        try {
            client.put().uri("/collections/{name}", collection)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 409) throw e;
        }
        for (String field : List.of("subject_id", "year", "rating", "tags", "content_hash")) {
            Map<String, Object> index = Map.of("field_name", field, "field_schema", field.equals("rating") ? "float" : field.equals("year") || field.equals("subject_id") ? "integer" : "keyword");
            try {
                client.put().uri("/collections/{name}/index", collection)
                        .contentType(MediaType.APPLICATION_JSON).body(index).retrieve().toBodilessEntity();
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() != 409) throw e;
            }
        }
    }

    public void upsert(List<KnowledgePoint> points) {
        if (points.isEmpty()) return;
        List<Map<String, Object>> values = new ArrayList<>();
        for (KnowledgePoint point : points) {
            values.add(Map.of("id", point.id(), "vector", point.vector(), "payload", point.payload()));
        }
        client.put().uri(uri -> uri.path("/collections/{name}/points").queryParam("wait", true).build(collection))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("points", values)).retrieve().toBodilessEntity();
    }

    /** 返回已存在条目的指纹，避免每次重跑都产生 embedding API 费用。 */
    public Map<Long, String> existingHashes(List<Long> subjectIds) {
        if (subjectIds.isEmpty()) return Map.of();
        Map<String, Object> body = Map.of(
                "filter", Map.of("must", List.of(Map.of("key", "subject_id", "match", Map.of("any", subjectIds)))),
                "with_payload", List.of("subject_id", "content_hash"),
                "limit", subjectIds.size());
        String raw = client.post().uri("/collections/{name}/points/scroll", collection)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        try {
            Map<?, ?> root = Json.MAPPER.readValue(raw, Map.class);
            Object result = root.get("result");
            if (!(result instanceof Map<?, ?> resultMap) || !(resultMap.get("points") instanceof List<?> points)) return Map.of();
            Map<Long, String> hashes = new LinkedHashMap<>();
            for (Object point : points) if (point instanceof Map<?, ?> item && item.get("payload") instanceof Map<?, ?> payload) {
                Object id = payload.get("subject_id");
                Object hash = payload.get("content_hash");
                if (id instanceof Number number && hash != null) hashes.put(number.longValue(), String.valueOf(hash));
            }
            return hashes;
        } catch (Exception e) {
            throw new IllegalStateException("解析 Qdrant 已有指纹失败", e);
        }
    }

    public List<KnowledgeHit> search(float[] vector, int limit, Map<String, Object> filters) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", vector);
        body.put("limit", limit);
        body.put("with_payload", true);
        Map<String, Object> qdrantFilter = filter(filters);
        if (!qdrantFilter.isEmpty()) body.put("filter", qdrantFilter);
        String raw = client.post().uri("/collections/{name}/points/search", collection)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
        try {
            Map<?, ?> root = Json.MAPPER.readValue(raw, Map.class);
            List<KnowledgeHit> hits = new ArrayList<>();
            Object result = root.get("result");
            if (result instanceof List<?> rows) for (Object row : rows) {
                if (!(row instanceof Map<?, ?> item)) continue;
                long id = ((Number) item.get("id")).longValue();
                double score = ((Number) item.get("score")).doubleValue();
                Map<String, Object> payload = item.get("payload") instanceof Map<?, ?> p ? castMap(p) : Map.of();
                String text = String.valueOf(payload.getOrDefault("text", ""));
                hits.add(new KnowledgeHit(id, score, text, payload));
            }
            return hits;
        } catch (Exception e) {
            throw new IllegalStateException("解析 Qdrant 检索响应失败", e);
        }
    }

    private static Map<String, Object> filter(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return Map.of();
        List<Map<String, Object>> must = new ArrayList<>();
        addRange(must, "year", filters.get("yearFrom"), "gte");
        addRange(must, "rating", filters.get("minRating"), "gte");
        if (filters.get("metaTags") instanceof List<?> tags && !tags.isEmpty()) {
            must.add(Map.of("key", "meta_tags", "match", Map.of("any", tags)));
        }
        return must.isEmpty() ? Map.of() : Map.of("must", must);
    }

    private static void addRange(List<Map<String, Object>> must, String key, Object value, String op) {
        if (value instanceof Number number) must.add(Map.of("key", key, "range", Map.of(op, number.doubleValue())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> source) {
        return (Map<String, Object>) source;
    }

    public record KnowledgePoint(long id, float[] vector, Map<String, Object> payload) {}
}
