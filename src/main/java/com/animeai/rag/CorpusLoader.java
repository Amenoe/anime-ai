package com.animeai.rag;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import com.animeai.config.AiProperties;
import com.animeai.tool.BangumiClient;

import org.springframework.stereotype.Component;

/** 从现有 anime 表构造 M2 文档；不写业务库。 */
@Component
public class CorpusLoader {
    private final AiProperties props;
    private final BangumiClient bangumi;

    public CorpusLoader(AiProperties props, BangumiClient bangumi) {
        this.props = props;
        this.bangumi = bangumi;
    }

    public List<KnowledgeDocument> loadFromMySql(Integer requestedLimit) {
        AiProperties.Corpus config = props.corpus();
        if (config.jdbcUrl() == null || config.jdbcUrl().isBlank()) {
            throw new IllegalStateException("未配置 AI_CORPUS_JDBC_URL，或直接在请求中传 documents");
        }
        int limit = requestedLimit == null || requestedLimit <= 0 ? 3000 : Math.min(requestedLimit, 10000);
        String sql = "SELECT anime_id,title,category,pub_time,description,season,rank,region,status FROM anime ORDER BY anime_id LIMIT ?";
        List<KnowledgeDocument> documents = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password());
                var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong("anime_id");
                    String title = text(rows.getString("title"));
                    String description = text(rows.getString("description"));
                    String content = "[标题] " + title + "\n[类型] " + text(rows.getString("category"))
                            + "\n[年份] " + text(rows.getString("pub_time")) + "\n[季度] " + text(rows.getString("season"))
                            + "\n[地区] " + text(rows.getString("region")) + "\n[状态] " + text(rows.getString("status"))
                            + "\n[排名] " + text(rows.getString("rank")) + "\n[简介] " + description;
                    documents.add(new KnowledgeDocument(id, content, Map.of(
                            "subject_id", id,
                            "name", title,
                            "year", year(rows.getString("pub_time")),
                            "text", content)));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 anime 表失败", e);
        }
        return documents;
    }

    /** Bangumi 排名浏览接口完整分页；限速 1 req/s，最多导入 3000 条。 */
    public List<KnowledgeDocument> loadFromBangumi(Integer requestedLimit) {
        int limit = requestedLimit == null || requestedLimit <= 0 ? 3000 : Math.min(requestedLimit, 3000);
        List<KnowledgeDocument> documents = new ArrayList<>(limit);
        for (int offset = 0; offset < limit; offset += 100) {
            if (offset > 0) {
                try { Thread.sleep(1000); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Bangumi 语料分页被中断", e);
                }
            }
            List<BangumiClient.Subject> subjects = bangumi.browseKnowledgeSubjects(Math.min(100, limit - offset), offset);
            if (subjects.isEmpty()) break;
            for (BangumiClient.Subject subject : subjects) documents.add(toDocument(subject));
            if (subjects.size() < 100) break;
        }
        return documents;
    }

    private static KnowledgeDocument toDocument(BangumiClient.Subject subject) {
        String name = text(subject.name());
        String nameCn = text(subject.nameCn());
        List<String> tags = subject.tags() == null ? List.of() : subject.tags().stream()
                .map(BangumiClient.Tag::name).filter(value -> value != null && !value.isBlank()).toList();
        List<String> metaTags = subject.metaTags() == null ? List.of() : subject.metaTags();
        String summary = text(subject.summary());
        int year = year(subject.date());
        Double rating = subject.rating() == null ? null : subject.rating().score();
        Integer rank = subject.rating() == null ? null : subject.rating().rank();
        String cover = subject.images() == null ? null : subject.images().small();
        String content = "[标题] " + (nameCn.isBlank() ? name : nameCn)
                + (nameCn.isBlank() || nameCn.equals(name) ? "" : " / " + name)
                + "\n[类型] 动画 " + String.join("、", metaTags)
                + "\n[标签] " + String.join("、", tags)
                + "\n[年份] " + year + " [评分] " + (rating == null ? "" : rating)
                + " [排名] " + (rank == null ? "" : rank)
                + "\n[简介] " + summary;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subject_id", subject.id());
        payload.put("name", name.isBlank() ? nameCn : name);
        payload.put("name_cn", nameCn.isBlank() ? name : nameCn);
        payload.put("tags", tags);
        payload.put("meta_tags", metaTags);
        payload.put("year", year);
        payload.put("rating", rating);
        payload.put("rank", rank);
        payload.put("date", subject.date());
        payload.put("cover", cover);
        return new KnowledgeDocument(subject.id(), content, payload);
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }

    private static int year(String value) {
        if (value == null || value.length() < 4) return 0;
        try { return Integer.parseInt(value.substring(0, 4)); } catch (NumberFormatException e) { return 0; }
    }
}
