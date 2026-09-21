package com.animeai.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.animeai.chat.dto.ChatRequest;
import com.animeai.model.AnimeCard;
import com.animeai.support.Json;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 把 {@link TokenStream} 的回调桥接成 SSE 事件。
 *
 * <p>事件映射关系：
 * <pre>
 *   onPartialResponse   → text-delta
 *   beforeToolExecution → tool-call
 *   onToolExecuted      → tool-result   （携带结构化番剧列表，前端直接渲染卡片）
 *   onCompleteResponse  → usage + done
 *   onError             → error + 关闭
 * </pre>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** SSE 连接总时限，比模型侧 60s 超时留出余量 */
    private static final long EMITTER_TIMEOUT_MS = 120_000L;

    private final AnimeAssistant assistant;

    public ChatService(AnimeAssistant assistant) {
        this.assistant = assistant;
    }

    public SseEmitter stream(ChatRequest request) {
        String conversationId = StringUtils.hasText(request.conversationId())
                ? request.conversationId()
                : UUID.randomUUID().toString();

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        SseWriter writer = new SseWriter(emitter);

        emitter.onTimeout(() -> {
            writer.send(SseEvent.ERROR, Map.of("message", "请求超时"));
            writer.complete();
        });
        emitter.onError(e -> log.debug("SSE 连接异常: {}", e.toString()));

        try {
            TokenStream stream = assistant.chat(conversationId, request.message());
            stream.onPartialResponse(text -> writer.send(SseEvent.TEXT_DELTA, Map.of("text", text)))
                    .beforeToolExecution(before -> writer.send(
                            SseEvent.TOOL_CALL,
                            Map.of(
                                    "name", before.request().name(),
                                    "args", jsonOrRaw(before.request().arguments()))))
                    .onToolExecuted(exec -> writer.send(SseEvent.TOOL_RESULT, toolResultPayload(exec)))
                    .onCompleteResponse(response -> {
                        writer.send(SseEvent.USAGE, usagePayload(response));
                        writer.send(SseEvent.DONE, donePayload(conversationId, response));
                        writer.complete();
                    })
                    .onError(error -> {
                        log.warn("模型流式返回错误: {}", error.toString());
                        writer.send(SseEvent.ERROR, Map.of("message", safeMessage(error)));
                        writer.complete();
                    })
                    .start();
        } catch (Exception e) {
            // 兜底：例如没配 API key 时会在发起阶段就抛，必须转成 SSE 错误事件，
            // 否则客户端拿到一个不结束的空连接，比直接报错更难排查。
            log.error("发起对话失败", e);
            writer.send(SseEvent.ERROR, Map.of("message", safeMessage(e)));
            writer.complete();
        }

        return emitter;
    }

    /** 工具结果：列表型结果额外挂到 subjects 字段，前端可直接当卡片数组用。 */
    private Map<String, Object> toolResultPayload(ToolExecution execution) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", execution.request().name());
        payload.put("failed", execution.hasFailed());

        Object result = execution.resultObject();
        if (result == null) {
            result = jsonOrRaw(execution.result());
        }
        if (result instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof AnimeCard) {
            payload.put("subjects", list);
        } else {
            payload.put("result", result);
        }
        return payload;
    }

    private Map<String, Object> usagePayload(ChatResponse response) {
        TokenUsage usage = response.tokenUsage();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (usage != null) {
            payload.put("promptTokens", usage.inputTokenCount());
            payload.put("completionTokens", usage.outputTokenCount());
            payload.put("totalTokens", usage.totalTokenCount());
        }
        return payload;
    }

    private Map<String, Object> donePayload(String conversationId, ChatResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put(
                "finishReason",
                response.finishReason() == null ? null : response.finishReason().name());
        return payload;
    }

    /** 把 JSON 字符串还原成对象，避免在 SSE 里嵌套一层转义字符串。 */
    private Object jsonOrRaw(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return Json.MAPPER.readTree(json);
        } catch (Exception e) {
            return json;
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return (message == null || message.isBlank()) ? error.getClass().getSimpleName() : message;
    }
}
