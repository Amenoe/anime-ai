package com.animeai.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 相关配置，全部可由环境变量覆盖（见 application.yml）。
 *
 * <p>敏感值（api-key）一律不写进配置文件，走环境变量注入：
 * {@code AI_CHAT_API_KEY} / {@code AI_EMBEDDING_API_KEY}。
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(
        Chat chat,
        Embedding embedding,
        Bangumi bangumi,
        Qdrant qdrant,
        Corpus corpus,
        Tmdb tmdb,
        Retrieval retrieval,
        String internalToken) {

    /** 对话模型（DeepSeek，OpenAI 兼容）。 */
    public record Chat(
            String baseUrl,
            String apiKey,
            String model,
            Double temperature,
            Duration timeout,
            Integer maxMessages) {}

    /**
     * 向量模型（SiliconFlow BGE-M3，OpenAI 兼容）。
     *
     * <p>注意：DeepSeek 没有 embedding 接口，所以这里必然是**第二家**供应商。
     */
    public record Embedding(String baseUrl, String apiKey, String model, Integer dimensions) {}

    public record Qdrant(String baseUrl, String collection, Integer topK) {}

    /**
     * TMDB（分集简介 / 角色集数）。
     *
     * @param token v4 Read Access Token，走 Authorization: Bearer。
     *              免费非商业用途，必须在应用内署名；缓存不得超过六个月。
     */
    public record Tmdb(String baseUrl, String token, String language) {}

    /**
     * 检索行为开关。
     *
     * @param strategy vector / hybrid / rrf / hybrid-rrf，留空即 hybrid
     * @param enabled  kill switch：false 时对话工具不调 embedding、直接返回空结果。
     *                 用来在 embedding 供应商限流/欠费时**保住对话主链路**，
     *                 不用改代码也不用回滚发布。
     */
    public record Retrieval(String strategy, Boolean enabled) {}

    /** 本地 anime 表连接；不配置 jdbc-url 时不启用自动入库。 */
    public record Corpus(String jdbcUrl, String username, String password, Integer batchSize) {}

    /** Bangumi 公开 API。 */
    public record Bangumi(String baseUrl, String userAgent, Duration timeout) {}
}
