package com.animeai.tool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.animeai.config.AiProperties;
import com.animeai.support.Json;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * TMDB 只读客户端：搜索剧集、取季与分集简介。
 *
 * <p>为什么用 TMDB 而不是 Bangumi 的分集接口：实测《无职转生》在 Bangumi 的
 * {@code episodes.desc} 覆盖率只有 **17%**（第一季 0%），而 TMDB 同作品第一季
 * **23/23 = 100%** 且是中文简介、里面带角色名。要回答「前两季讲了什么」和
 * 「某角色第几集出现」，数据源必须换。
 *
 * <p>免费使用的前提（TMDB API Terms）：
 * <ul>
 *   <li>非商业用途 + 在应用 About/Credits 里放 logo 与声明
 *       "This product uses the TMDB API but is not endorsed or certified by TMDB."</li>
 *   <li><b>缓存不得超过六个月</b>。所以下面的缓存带 TTL（默认 24 小时），
 *       ⚠️ 不要为了"性能"把 TTL 去掉或改成永久缓存 —— 那违反条款。</li>
 *   <li>速率上限约 40 次/秒（老的 40次/10秒 已于 2019-12-16 取消），
 *       遇 429 应退避重试。</li>
 * </ul>
 */
@Component
public class TmdbClient {

    private static final Logger log = LoggerFactory.getLogger(TmdbClient.class);

    /** 缓存 TTL。条款上限是六个月，这里取 24 小时：既省请求又不至于长期陈旧。 */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final RestClient client;
    private final boolean enabled;
    private final String language;

    /** key = 请求路径+查询串；value = 缓存体（含写入时间） */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(String body, long storedAtMillis) {
        boolean fresh() {
            return System.currentTimeMillis() - storedAtMillis < CACHE_TTL.toMillis();
        }
    }

    public TmdbClient(AiProperties props) {
        AiProperties.Tmdb config = props.tmdb();
        this.enabled = config != null && StringUtils.hasText(config.token());
        this.language = config == null || !StringUtils.hasText(config.language()) ? "zh-CN" : config.language();
        String baseUrl = config == null || !StringUtils.hasText(config.baseUrl())
                ? "https://api.themoviedb.org/3"
                : config.baseUrl().replaceAll("/+$", "");
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        if (enabled) {
            // v4 Read Access Token 走 Bearer：不会像 v3 api_key 那样出现在 URL 与日志里
            builder = builder.defaultHeader("Authorization", "Bearer " + config.token());
        }
        this.client = builder.build();
    }

    public boolean enabled() {
        return enabled;
    }

    /** 按标题搜索剧集（中文优先）。 */
    public List<SeriesHit> searchSeries(String query, int limit) {
        if (!enabled || !StringUtils.hasText(query)) return List.of();
        String body = get("/search/tv", "query=" + encode(query) + "&include_adult=false&page=1");
        if (body == null) return List.of();
        try {
            SearchResponse response = Json.MAPPER.readValue(body, SearchResponse.class);
            if (response == null || response.results() == null) return List.of();
            return response.results().stream().limit(limit).toList();
        } catch (Exception e) {
            log.warn("TMDB 搜索解析失败: {}", e.toString());
            return List.of();
        }
    }

    /** 剧集详情：季列表。 */
    public SeriesDetail seriesDetail(int seriesId) {
        if (!enabled) return null;
        String body = get("/tv/" + seriesId, "");
        if (body == null) return null;
        try {
            return Json.MAPPER.readValue(body, SeriesDetail.class);
        } catch (Exception e) {
            log.warn("TMDB 剧集详情解析失败: {}", e.toString());
            return null;
        }
    }

    /** 某一季的全部分集（含简介）。 */
    public List<Episode> season(int seriesId, int seasonNumber) {
        if (!enabled) return List.of();
        String body = get("/tv/" + seriesId + "/season/" + seasonNumber, "");
        if (body == null) return List.of();
        try {
            SeasonDetail detail = Json.MAPPER.readValue(body, SeasonDetail.class);
            return detail == null || detail.episodes() == null ? List.of() : detail.episodes();
        } catch (Exception e) {
            log.warn("TMDB 季详情解析失败: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 带缓存与重试的 GET。
     *
     * <p>429 退避一次即可：这是「尽力而为」的增强数据，重试太多次会把响应时间拖长，
     * 而模型侧还有自己的超时。
     */
    private String get(String path, String query) {
        String key = path + "?" + query + "&language=" + language;
        Cached hit = cache.get(key);
        if (hit != null && hit.fresh()) return hit.body();

        String uri = StringUtils.hasText(query) ? path + "?" + query + "&language=" + language
                : path + "?language=" + language;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String body = client.get().uri(uri).accept(MediaType.APPLICATION_JSON)
                        .retrieve().body(String.class);
                if (body != null) cache.put(key, new Cached(body, System.currentTimeMillis()));
                return body;
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                log.warn("TMDB 限流，退避重试: {}", path);
                try {
                    Thread.sleep(1200L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } catch (Exception e) {
                log.warn("TMDB 请求失败 {}: {}", path, e.toString());
                return null;
            }
        }
        return null;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── 响应模型（只声明用到的字段，其余忽略）──────────────────
    // 注意：这里必须用 Jackson 2 的注解，与 LangChain4j 同源（见 CLAUDE.md「Jackson 2/3 并存」）

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResponse(List<SeriesHit> results) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record SeriesHit(
            int id,
            String name,
            @com.fasterxml.jackson.annotation.JsonProperty("original_name") String originalName,
            @com.fasterxml.jackson.annotation.JsonProperty("first_air_date") String firstAirDate,
            String overview) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record SeriesDetail(
            int id,
            String name,
            @com.fasterxml.jackson.annotation.JsonProperty("original_name") String originalName,
            List<Season> seasons) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Season(
            @com.fasterxml.jackson.annotation.JsonProperty("season_number") int seasonNumber,
            String name,
            @com.fasterxml.jackson.annotation.JsonProperty("episode_count") int episodeCount,
            @com.fasterxml.jackson.annotation.JsonProperty("air_date") String airDate) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record SeasonDetail(
            @com.fasterxml.jackson.annotation.JsonProperty("season_number") Integer seasonNumber,
            List<Episode> episodes) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Episode(
            @com.fasterxml.jackson.annotation.JsonProperty("episode_number") int episodeNumber,
            String name,
            String overview,
            @com.fasterxml.jackson.annotation.JsonProperty("air_date") String airDate) {}
}
