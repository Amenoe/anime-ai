package com.animeai.config;

import com.animeai.chat.AnimeAssistant;
import com.animeai.tool.AnimeTools;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 组件装配。
 *
 * <p>这里**不用** langchain4j-spring-boot4-starter，而是手工声明 bean：
 * 该 starter 仍在 beta 线（1.20.0-beta30），属性名与自动装配行为可能在小版本间变动；
 * M1 阶段显式装配更好排查，也少一层不确定性。
 */
@Configuration
public class AiConfig {

    @Bean
    public StreamingChatModel streamingChatModel(AiProperties props) {
        AiProperties.Chat chat = props.chat();
        return OpenAiStreamingChatModel.builder()
                .baseUrl(chat.baseUrl())
                .apiKey(chat.apiKey())
                .modelName(chat.model())
                .temperature(chat.temperature())
                .timeout(chat.timeout())
                .build();
    }

    /**
     * 每个会话一个滑动窗口记忆，避免上下文无限增长（同时控制 token 成本）。
     * memoryId 即 conversationId。
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(AiProperties props) {
        int maxMessages = props.chat().maxMessages() == null ? 20 : props.chat().maxMessages();
        return memoryId -> MessageWindowChatMemory.withMaxMessages(maxMessages);
    }

    /**
     * 向量模型：M1 用不到，只有配了 api-key 才创建。
     * 这样 M1 只配 DeepSeek key 就能跑（SiliconFlow 的 key 到 M2 再补）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "ai.embedding", name = "api-key")
    public EmbeddingModel embeddingModel(AiProperties props) {
        AiProperties.Embedding emb = props.embedding();
        OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
                .baseUrl(emb.baseUrl())
                .apiKey(emb.apiKey())
                .modelName(emb.model());
        if (emb.dimensions() != null) {
            builder.dimensions(emb.dimensions());
        }
        return builder.build();
    }

    /**
     * 装配带工具调用能力的助手。AiServices 会自动完成
     * 「模型请求调工具 → 执行工具 → 把结果回灌模型」的多轮循环，
     * 所以这里不需要手写 tool loop。
     */
    @Bean
    public AnimeAssistant animeAssistant(
            StreamingChatModel streamingChatModel,
            ChatMemoryProvider chatMemoryProvider,
            AnimeTools animeTools) {
        return AiServices.builder(AnimeAssistant.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(animeTools)
                .build();
    }
}
