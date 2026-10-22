package com.animeai.chat;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

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

    /**
     * @param today 当前日期（YYYY-MM-DD）。必须由调用方注入 —— 模型自己不知道今天是哪天，
     *              而「这个季度/本季/新番」这类请求全靠它推算年月。实测不注入时，
     *              模型会先查总榜、再反复换季度试，最后撞上工具调用上限整轮失败。
     */
    @SystemMessage(fromResource = "prompts/system-prompt.txt")
    TokenStream chat(@V("today") String today, @UserMessage String message);
}
