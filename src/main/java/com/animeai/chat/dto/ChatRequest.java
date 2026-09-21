package com.animeai.chat.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 对话请求。
 *
 * <p><b>本服务无状态</b>：会话历史由上游（anime-chat-server 的 MySQL）持有，
 * 每次请求把窗口内的历史一并带过来。这样做的好处是：
 * <ul>
 *   <li>历史只有一个权威来源，不会出现「本服务重启后忘了、但界面还显示着」的不一致；</li>
 *   <li>本服务可以随时重启/多实例部署，不需要粘性会话。</li>
 * </ul>
 *
 * @param messages 完整消息列表（含本轮用户消息），最后一条必须是 user
 * @param message  单轮便捷写法；仅在没有 messages 时使用，等价于只有一条 user 消息
 */
public record ChatRequest(
        @Size(max = 64, message = "conversationId 过长") String conversationId,
        @Valid List<ChatMessageDto> messages,
        @Size(max = 2000, message = "message 过长") String message) {

    /** 单条历史消息。role 只接受 user / assistant。 */
    public record ChatMessageDto(
            @NotBlank(message = "role 不能为空") String role,
            @NotBlank(message = "content 不能为空") @Size(max = 8000, message = "content 过长") String content) {}

    /** 取出本次要发给模型的消息列表；两种入参写法二选一。 */
    public List<ChatMessageDto> resolveMessages() {
        if (messages != null && !messages.isEmpty()) {
            return messages;
        }
        if (message != null && !message.isBlank()) {
            return List.of(new ChatMessageDto("user", message));
        }
        return List.of();
    }

    public boolean isEmpty() {
        return resolveMessages().isEmpty();
    }
}
