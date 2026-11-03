package com.animeai.tool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.animeai.config.AiProperties;
import com.animeai.model.AnimeCard;
import com.animeai.support.Json;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Bangumi 公开 API 客户端。
 *
 * <p>接口文档：https://bangumi.github.io/api/ （OpenAPI 见仓库 bangumi/api）
 *
 * <p>两个必须注意的点：
 * <ul>
 *   <li>必须带 {@code User-Agent}，否则被拒；</li>
 *   <li>{@code POST /v0/search/subjects} 的 {@code keyword} 是**必填且不能为空串**，
 *       空串会直接 400。调用方需自行兜底。</li>
 * </ul>
 */
@Component
public class BangumiClient {

    private static final Logger log = LoggerFactory.getLogger(BangumiClient.class);

    /** 条目类型：2 = 动画 */
    private static final int TYPE_ANIME = 2;

    /** 简介截断长度：整段简介进上下文会浪费 token，检索展示也用不到那么长 */
    private static final int MAX_SUMMARY_CHARS = 120;
    /** 标签只保留前几个 */
    private static final int MAX_TAGS = 5;

    /**
     * 一次调用最多尝试次数（首次 + 重试 1 次）。
     *
     * <p>生产走 Cloudflare 反代，跨境链路实测同一请求 1.7s–30s 抖动；
     * 反代侧对 GET 有 10 分钟缓存，重试往往能命中缓存或落到另一个边缘节点，
     * 成本远低于让用户把问题重问一遍。
     */
    private static final int MAX_ATTEMPTS = 2;

    /** 重试间隔：给反代/上游一点恢复时间，又不会明显拉长等待 */
    private static final long RETRY_DELAY_MS = 300;

    private final RestClient client;

    public BangumiClient(AiProperties props) {
        AiProperties.Bangumi bangumi = props.bangumi();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(bangumi.timeout());

        this.client = RestClient.builder()
                .baseUrl(bangumi.baseUrl().replaceAll("/+$", ""))
                .defaultHeader("User-Agent", bangumi.userAgent())
                .requestFactory(factory)
                .build();
    }

    /**
     * 检索动画条目。
     *
     * @param keyword  关键词，**必须非空**
     * @param tags     标签（中文），多值之间是「且」
     * @param metaTags 公共标签（TV / WEB / OVA / 原创 / 漫画改 …），多值之间是「且」
     * @param minRating 最低评分（含）
     * @param yearFrom  最早放送年份（含）
     * @param sort      match / heat / rank / score
     */
    public List<AnimeCard> searchSubjects(
            String keyword,
            List<String> tags,
            List<String> metaTags,
            Double minRating,
            Integer yearFrom,
            String sort,
            int limit,
            int offset) {

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("type", List.of(TYPE_ANIME));
        filter.put("nsfw", false);
        if (tags != null && !tags.isEmpty()) {
            filter.put("tag", tags);
        }
        if (metaTags != null && !metaTags.isEmpty()) {
            filter.put("meta_tags", metaTags);
        }
        if (minRating != null) {
            filter.put("rating", List.of(">=" + minRating));
        }
        if (yearFrom != null) {
            filter.put("air_date", List.of(">=" + yearFrom + "-01-01"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyword", keyword);
        body.put("sort", (sort == null || sort.isBlank()) ? "match" : sort);
        body.put("filter", filter);

        SearchResponse response;
        try {
            // 请求与解析一起重试：反代偶尔会回 200 + HTML 错误页，只重试网络层救不回来
            response = retry("POST /v0/search/subjects", () -> {
                String raw = client.post()
                        .uri(uriBuilder -> uriBuilder
                                .path("/v0/search/subjects")
                                .queryParam("limit", limit)
                                .queryParam("offset", offset)
                                .build())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return parse(raw, SearchResponse.class, "搜索");
            });
        } catch (RuntimeException e) {
            log.warn("bangumi 搜索失败（已重试 {} 次）: keyword={} tags={} metaTags={} err={}",
                    MAX_ATTEMPTS - 1, keyword, tags, metaTags, e.toString());
            return List.of();
        }

        if (response == null || response.data() == null) {
            log.warn("bangumi 搜索返回空: keyword={} tags={} metaTags={}", keyword, tags, metaTags);
            return List.of();
        }
        return response.data().stream().map(BangumiClient::toCard).toList();
    }

    /**
     * 浏览动画条目：{@code GET /v0/subjects}。
     *
     * <p><b>这是「推荐番剧」的正确入口</b>，实测质量远高于关键词搜索：
     * {@code sort=rank} 会返回真正的经典高分作品（攻壳机动队 9.2、星际牛仔 9.1、命运石之门 8.8），
     * 配合 {@code year}/{@code month} 可精确到某季新番。
     *
     * <p>原因见 {@link #searchSubjects} 的说明：关键词搜索只能做标题匹配，
     * 「按标签发现」这条路在 Bangumi 搜索接口上是走不通的。
     *
     * @param year  放送年份，可空
     * @param month 放送月份（1-12），可空；仅在给了 year 时有意义
     * @param sort  rank（按排名，推荐）/ date（按放送日期）
     */
    public List<AnimeCard> browseSubjects(Integer year, Integer month, String sort, int limit, int offset) {
        String safeSort = ("date".equalsIgnoreCase(sort)) ? "date" : "rank";

        PagedResponse response;
        try {
            response = retry("GET /v0/subjects", () -> {
                String raw = client.get()
                        .uri(uriBuilder -> {
                            uriBuilder.path("/v0/subjects")
                                    .queryParam("type", TYPE_ANIME)
                                    .queryParam("sort", safeSort)
                                    .queryParam("limit", limit)
                                    .queryParam("offset", offset);
                            if (year != null) {
                                uriBuilder.queryParam("year", year);
                            }
                            if (month != null && year != null) {
                                uriBuilder.queryParam("month", month);
                            }
                            return uriBuilder.build();
                        })
                        .retrieve()
                        .body(String.class);
                // 浏览接口的响应体形如 { data: [...], total, limit, offset }
                return parse(raw, PagedResponse.class, "浏览");
            });
        } catch (RuntimeException e) {
            log.warn("bangumi 浏览失败（已重试 {} 次）: year={} month={} sort={} err={}",
                    MAX_ATTEMPTS - 1, year, month, safeSort, e.toString());
            return List.of();
        }

        if (response == null || response.data() == null) {
            return List.of();
        }
        return response.data().stream().map(BangumiClient::toCard).toList();
    }

    /** 用于离线知识库导入：保留完整简介和完整标签，不裁成对话卡片格式。 */
    public List<Subject> browseKnowledgeSubjects(int limit, int offset) {
        try {
            PagedResponse response = retry("GET /v0/subjects (knowledge)", () -> {
                String raw = client.get()
                        .uri(uriBuilder -> uriBuilder.path("/v0/subjects")
                                .queryParam("type", TYPE_ANIME).queryParam("sort", "rank")
                                .queryParam("limit", limit).queryParam("offset", offset).build())
                        .retrieve().body(String.class);
                return parse(raw, PagedResponse.class, "知识库分页");
            });
            return response == null || response.data() == null ? List.of() : response.data();
        } catch (RuntimeException e) {
            log.warn("bangumi 知识库分页失败（已重试 {} 次）: {}", MAX_ATTEMPTS - 1, e.toString());
            return List.of();
        }
    }

    // ── 重试与解析 ──────────────────────────────────────────────

    /**
     * 带一次重试地执行 Bangumi 请求。
     *
     * <p>为什么不只是调大超时：跨境链路的问题不是「慢」而是「时快时慢」，
     * 单纯调超时只能把快速失败变成慢速失败。反代对 GET 有 10 分钟缓存，
     * 重试大概率命中缓存或换一个边缘节点，成本很低。
     *
     * <p>只重试可恢复的错误：网络/超时、5xx、429，以及「响应体不是合法 JSON」
     * （反代偶尔会回 HTML 错误页）；其它 4xx 是参数问题，重试没有意义。
     */
    private <T> T retry(String label, Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long started = System.nanoTime();
            try {
                return call.get();
            } catch (RuntimeException e) {
                last = e;
                long costMs = (System.nanoTime() - started) / 1_000_000;
                if (attempt >= MAX_ATTEMPTS || !isRetryable(e)) {
                    break;
                }
                log.warn("bangumi {} 第 {} 次失败（{} ms），{} ms 后重试: {}",
                        label, attempt, costMs, RETRY_DELAY_MS, e.toString());
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last == null ? new IllegalStateException("bangumi 请求未执行: " + label) : last;
    }

    private static boolean isRetryable(RuntimeException e) {
        if (e instanceof ResourceAccessException) {
            return true;
        }
        if (e instanceof HttpServerErrorException) {
            return true;
        }
        if (e instanceof HttpClientErrorException) {
            // 4xx 里只有 429 值得重试，其余是参数/鉴权问题
            return e instanceof HttpClientErrorException.TooManyRequests;
        }
        // 反代回 HTML 错误页时，RestClient 会抛基类异常；这类也重试一次
        return e instanceof RestClientException;
    }

    private static <T> T parse(String raw, Class<T> type, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BangumiResponseException(label + " 返回空响应体");
        }
        try {
            return Json.MAPPER.readValue(raw, type);
        } catch (Exception e) {
            // Bangumi 的 schema 会演进（例如 rating.count 是分数分布对象而不是整数），
            // 打真实接口才暴露得出来。把原始响应的头部一起记下来，方便直接定位字段错配。
            log.warn("bangumi {} 响应解析失败: {} | 原始响应前 800 字: {}", label, e.toString(), abbreviate(raw, 800));
            throw new BangumiResponseException(label + " 响应不是合法 JSON", e);
        }
    }

    /** 上游返回了非预期响应体（常见于反代返回 HTML 错误页）。 */
    private static class BangumiResponseException extends RuntimeException {
        BangumiResponseException(String message) {
            super(message);
        }

        BangumiResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static AnimeCard toCard(Subject subject) {
        Double score = subject.rating() == null ? null : subject.rating().score();
        Integer rank = subject.rating() == null ? null : subject.rating().rank();
        String cover = subject.images() == null ? null : subject.images().small();

        // Bangumi 有相当一部分条目的 name_cn 是**空字符串**（字段存在但为空），
        // 直接透传会让前端渲染出没有标题的卡片。这里统一兜底到原名。
        String displayName = isBlank(subject.nameCn()) ? subject.name() : subject.nameCn();

        List<String> tagNames = new ArrayList<>();
        if (subject.tags() != null) {
            subject.tags().stream()
                    .map(Tag::name)
                    .filter(n -> n != null && !n.isBlank())
                    .limit(MAX_TAGS)
                    .forEach(tagNames::add);
        }

        return new AnimeCard(
                subject.id(),
                subject.name(),
                displayName,
                cover,
                score,
                rank,
                subject.date(),
                tagNames,
                truncate(subject.summary(), MAX_SUMMARY_CHARS));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "<null>";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    // ── Bangumi 响应体（只声明用得到的字段，其余忽略） ──────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResponse(List<Subject> data, Integer total) {}

    /** 浏览接口的分页响应（{@code GET /v0/subjects}）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PagedResponse(List<Subject> data, Integer total, Integer limit, Integer offset) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Subject(
            long id,
            String name,
            @JsonProperty("name_cn") String nameCn,
            Images images,
            Rating rating,
            String date,
            List<Tag> tags,
            @JsonProperty("meta_tags") List<String> metaTags,
            String summary) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Images(String large, String common, String medium, String small, String grid) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rating(Integer rank, Integer total, Double score) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tag(String name, Integer count) {}
}
