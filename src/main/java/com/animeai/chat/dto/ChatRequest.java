package com.animeai.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 对话请求。
 *
 * @param conversationId 会话 id；不传则服务端生成一个并在 done 事件里回传。
 *                       它同时是 LangChain4j 的记忆 id，决定复用哪份上下文。
 * @param message        用户这一轮说的话
 */
public record ChatRequest(
        @Size(max = 64, message = "conversationId 过长") String conversationId,
        @NotBlank(message = "message 不能为空") @Size(max = 2000, message = "message 过长") String message) {}
