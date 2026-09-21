package com.animeai.chat;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 番剧助手接口，由 {@code AiServices} 生成实现。
 *
 * <p>返回 {@link TokenStream} 而不是 String，是为了拿到逐 token 回调，
 * 直接映射到 SSE 的 {@code text-delta} 事件。
 *
 * <p>{@code @MemoryId} 标记的参数决定用哪一份会话记忆（即 conversationId）。
 * 系统提示词放在 resources/prompts 下，便于迭代而不用改代码。
 */
public interface AnimeAssistant {

    @SystemMessage(fromResource = "prompts/system-prompt.txt")
    TokenStream chat(@MemoryId String conversationId, @UserMessage String message);
}
