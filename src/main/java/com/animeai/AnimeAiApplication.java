package com.animeai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * anime-ai：番剧 AI 服务。
 *
 * <p>M1 目标：打通「NestJS → AI 服务 → DeepSeek」的 SSE 链路 + 一个 Bangumi 检索工具，
 * 不含 RAG（向量检索是 M2）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AnimeAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnimeAiApplication.class, args);
    }
}
