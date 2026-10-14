package com.animeai.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 组件装配。
 *
 * <p>这里**不用** langchain4j-spring-boot4-starter，而是手工声明 bean：
 * 该 starter 仍在 beta 线（1.20.0-beta30），属性名与自动装配行为可能在小版本间变动；
 * 显式装配更好排查，也少一层不确定性。
 *
 * <p>注意这里**不装配** AnimeAssistant：本服务无状态，
 * 助手实例需要绑定「本次请求专属」的 ChatMemory（预置上游传来的历史），
 * 因此由 {@code ChatService} 按请求构造，不能做成单例 bean。
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
     * 向量模型：M1 用不到，只有配了 api-key 才创建。
     * 这样 M1 只配 DeepSeek key 就能跑（SiliconFlow 的 key 到 M2 再补）。
     */
    @Bean
    @ConditionalOnExpression("'${ai.embedding.api-key:}' != ''")
    public EmbeddingModel embeddingModel(AiProperties props) {
        AiProperties.Embedding emb = props.embedding();
        // BGE-M3 的输出维度固定为 1024。SiliconFlow 兼容接口不接受额外的 dimensions 参数，
        // 传入 dimensions=1024 会返回 20015「The parameter is invalid」。Qdrant 维度仍由配置声明。
        return OpenAiEmbeddingModel.builder()
                .baseUrl(emb.baseUrl())
                .apiKey(emb.apiKey())
                .modelName(emb.model())
                .build();
    }
}
