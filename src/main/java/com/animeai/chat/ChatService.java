package com.animeai.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.animeai.chat.dto.ChatRequest;
import com.animeai.config.AiProperties;
import com.animeai.model.AnimeCard;
import com.animeai.support.Json;
import com.animeai.tool.AnimeTools;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
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
 *
 * <p><b>无状态设计</b>：每次请求用上游传来的历史临时构造一份 ChatMemory，只服务于本次调用，
 * 不跨请求保留。历史窗口由上游（anime-chat-server）决定，这里不再二次裁剪，
 * 避免两处各裁一次导致上下文莫名丢失。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** SSE 连接总时限，比模型侧 60s 超时留出余量 */
    private static final long EMITTER_TIMEOUT_MS = 120_000L;

    /**
     * 单轮对话最多允许多少次工具往返。
     *
     * 与记忆窗口大小是一对：窗口按它预留槽位，它则防止失控循环把窗口顶掉。
     * 取值与 docs/ai-rag-practice-roadmap.md §7 的「maxToolRounds 默认 5」一致。
     */
    private static final int MAX_TOOL_ROUNDS = 5;

    /**
     * 越界拒答单独用一个 finishReason，而不是伪装成 {@code STOP}。
     *
     * <p>理由：这一轮**没有调用模型**，把「被守卫拦下」混进正常回答里，看板上就分不出
     * 「模型答的」和「前端式回绝」，也就无法评估守卫的命中率与误伤率。
     * 上游 NestJS 网关只把 finishReason 当字符串透传进埋点（前端忽略未知值），
     * 所以新增取值是向后兼容的。
     */
    private static final String FINISH_REASON_SCOPE_REJECTED = "SCOPE_REJECTED";

    /** 被拦截时只记前 60 字，避免把整段用户输入写进日志 */
    private static final int LOG_SNIPPET_MAX = 60;

    private final StreamingChatModel streamingChatModel;
    private final AnimeTools animeTools;
    private final AiProperties props;

    public ChatService(
            StreamingChatModel streamingChatModel, AnimeTools animeTools, AiProperties props) {
        this.streamingChatModel = streamingChatModel;
        this.animeTools = animeTools;
        this.props = props;
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

        List<ChatRequest.ChatMessageDto> messages = request.resolveMessages();
        if (messages.isEmpty()) {
            writer.send(SseEvent.ERROR, Map.of("message", "messages 与 message 不能同时为空"));
            writer.complete();
            return emitter;
        }

        String prompt = messages.get(messages.size() - 1).content();

        // 话题范围守卫：在**发起模型调用之前**判定，命中就直接回绝 —— 全程 0 token。
        //
        // 只把「本轮用户输入」交给守卫，不看 history：把历史拉进来会让规则不再可判定
        // （任何跑题请求都能靠「上一轮聊过番」混过去），详见 TopicScopeGuard 的类注释。
        TopicScopeGuard.Decision scope = TopicScopeGuard.judge(prompt);
        if (!scope.allowed()) {
            log.info(
                    "话题范围拦截 conversationId={} reason={} 输入长度={}",
                    conversationId, scope.reason(), prompt.length());
            log.debug("被拦截的输入片段: {}", abbreviate(prompt));
            writeScopeRejection(writer, conversationId, scope.reason());
            return emitter;
        }

        try {
            List<ChatMessage> history = toHistory(messages.subList(0, messages.size() - 1));

            // 只服务于本次请求的记忆。
            //
            // ⚠️ 窗口大小必须覆盖「本轮 user + 全部工具往返」，否则模型会在工具返回后**丢掉用户的问题**：
            //    一次工具往返要占 3 个槽 —— user、ai(toolCall)、toolResult。
            //    曾经写成 history.size() + 2，单轮请求时窗口只有 2：
            //    toolResult 一进来就把 user 挤出去，第二次请求的上下文里只剩「工具调用+结果」，
            //    模型于是答非所问地重新打招呼（实测踩到）。下面按最大工具轮数预留。
            int window = history.size() + 1 + 2 * MAX_TOOL_ROUNDS;
            MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(window);
            if (!history.isEmpty()) {
                memory.set(history);
            }

            AnimeAssistant assistant = AiServices.builder(AnimeAssistant.class)
                    .streamingChatModel(streamingChatModel)
                    .chatMemory(memory)
                    .tools(animeTools)
                    // 兜住失控的工具循环：它与上面的窗口大小是**一对**，
                    // 少了这个上限，模型可以一直调工具，窗口再大也会被顶掉。
                    .maxToolCallingRoundTrips(MAX_TOOL_ROUNDS)
                    .build();

            log.debug(
                    "stream conversationId={} 历史 {} 条，本轮长度 {}",
                    conversationId, history.size(), prompt.length());

            TokenStream stream = assistant.chat(prompt);
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

    /**
     * 走**正常 SSE 流**把拒答文案发出去 —— 不是 HTTP 4xx，也不发 {@code error} 事件。
     *
     * <p>三点都是刻意的：
     * <ol>
     *   <li><b>不是错误</b>：拒答是正常业务结果。走 4xx 的话前端只有一套「出错了」的展示，
     *       而网关还会把它记成 {@code status=error} 的埋点，把「守卫拦下」污染成「服务故障」。</li>
     *   <li><b>仍然发 usage</b>：前端与网关都按「text-delta → usage → done」认定一轮完整结束。
     *       值全是 0 —— 这一轮确实一个 token 都没花，这正是前置拦截的意义。</li>
     *   <li><b>done 里 model 为 null</b>：没有调用任何模型。填上配置里的模型名会让人误以为
     *       模型参与过（网关那边只在 {@code typeof model === 'string'} 时取值，null 会被自然忽略）。</li>
     * </ol>
     */
    private void writeScopeRejection(
            SseWriter writer, String conversationId, TopicScopeGuard.Reason reason) {
        writer.send(SseEvent.TEXT_DELTA, Map.of("text", scopeRejectionText(reason)));
        writer.send(
                SseEvent.USAGE,
                Map.of("promptTokens", 0, "completionTokens", 0, "totalTokens", 0));
        writer.send(SseEvent.DONE, scopeDonePayload(conversationId));
        writer.complete();
    }

    private Map<String, Object> scopeDonePayload(String conversationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("finishReason", FINISH_REASON_SCOPE_REJECTED);
        payload.put("model", null);
        return payload;
    }

    /**
     * 拒答文案。要求：短、不爹味、说清只聊动漫，并给一个**可执行**的引导。
     *
     * <p>按原因分两版：越权/身份类是「不参与这类设定」，跑题类是「换个动漫问题」。
     * 只有前三种 reason 会走到这里（EMPTY / ANIME_SIGNAL / NO_SIGNAL 都是放行）。
     */
    private static String scopeRejectionText(TopicScopeGuard.Reason reason) {
        return switch (reason) {
            case INJECTION_ATTEMPT, IDENTITY_PROBE ->
                    "我只聊动漫相关的事，别的设定就不参与了～"
                            + "想找番的话，直接说类型、年份或「类似某某」就行。";
            default ->
                    "我只能聊动漫相关的话题～想看什么类型？"
                            + "给个关键词或年份就行，比如「2020 年之后的恋爱番」。";
        };
    }

    private static String abbreviate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= LOG_SNIPPET_MAX
                ? flat
                : flat.substring(0, LOG_SNIPPET_MAX) + "…";
    }

    /** 上游传来的 role/content 映射为 LangChain4j 的消息类型；未知 role 当作用户消息。 */
    private static List<ChatMessage> toHistory(List<ChatRequest.ChatMessageDto> messages) {
        List<ChatMessage> history = new ArrayList<>(messages.size());
        for (ChatRequest.ChatMessageDto m : messages) {
            if ("assistant".equalsIgnoreCase(m.role())) {
                history.add(AiMessage.from(m.content()));
            } else {
                history.add(UserMessage.from(m.content()));
            }
        }
        return history;
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
        // 带上模型名：上游（NestJS 网关）要做用量统计，但它自己不知道实际用的是哪个模型，
        // 只有这里知道。前端忽略多余字段，所以加它不影响既有解析。
        payload.put("model", props.chat().model());
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
