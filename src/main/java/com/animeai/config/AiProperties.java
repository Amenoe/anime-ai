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
public record AiProperties(Chat chat, Embedding embedding, Bangumi bangumi, String internalToken) {

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

    /** Bangumi 公开 API。 */
    public record Bangumi(String baseUrl, String userAgent, Duration timeout) {}
}
