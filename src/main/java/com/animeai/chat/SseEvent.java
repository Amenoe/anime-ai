package com.animeai.chat;

/**
 * SSE 事件名。协议与 docs/ai-chat-tech-stack.md §4.5 一致。
 *
 * <p>前端是**手写** fetch + ReadableStream 解析的，所以这份协议是两端的唯一契约：
 * 新增事件时前端遇到未知 event 会忽略，因此可以向后兼容地扩展。
 */
public final class SseEvent {

    /** 模型输出的文本增量：{"text":"..."} */
    public static final String TEXT_DELTA = "text-delta";
    /** 模型请求调用工具：{"name":"search_anime","args":{...}} */
    public static final String TOOL_CALL = "tool-call";
    /** 工具执行完成：{"name":"search_anime","failed":false,"subjects":[...]} */
    public static final String TOOL_RESULT = "tool-result";
    /** token 用量：{"promptTokens":812,"completionTokens":233} */
    public static final String USAGE = "usage";
    /** 出错：{"message":"..."} */
    public static final String ERROR = "error";
    /** 结束：{"conversationId":"...","finishReason":"STOP"} */
    public static final String DONE = "done";

    private SseEvent() {}
}
