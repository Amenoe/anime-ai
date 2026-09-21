package com.animeai.chat;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 番剧助手接口，由 {@code AiServices} 生成实现。
 *
 * <p>返回 {@link TokenStream} 而不是 String，是为了拿到逐 token 回调，
 * 直接映射到 SSE 的 {@code text-delta} 事件。
 *
 * <p><b>刻意不声明 {@code @MemoryId}</b>：本服务无状态，会话历史由上游 MySQL 提供，
 * 每次请求在 {@link ChatService} 里构造一份「只属于本次请求」的 ChatMemory 并预置历史。
 * 这样历史只有一个权威来源，本服务重启不影响上下文连续性。
 *
 * <p>系统提示词放在 resources/prompts 下，便于迭代而不用改代码。
 */
public interface AnimeAssistant {

    @SystemMessage(fromResource = "prompts/system-prompt.txt")
    TokenStream chat(@UserMessage String message);
}
