package com.animeai.chat;

import java.util.concurrent.atomic.AtomicBoolean;

import com.animeai.support.Json;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 写出工具。
 *
 * <p>两个必须处理的现实问题：
 * <ol>
 *   <li><b>客户端断开是常态</b>（用户关页面、切标签页），此时 {@code send} 会抛异常。
 *       不能让它冒泡打断模型的流式回调，否则日志会被刷满。</li>
 *   <li><b>data 必须是单行 JSON</b>。SSE 以换行分隔字段，多行 JSON 会把一条事件拆成多条。
 *       Jackson 的 writeValueAsString 默认就是单行，这里再兜一次底。</li>
 * </ol>
 */
public class SseWriter {

    private static final Logger log = LoggerFactory.getLogger(SseWriter.class);

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SseWriter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public void send(String event, Object payload) {
        if (closed.get()) {
            return;
        }
        try {
            String json = Json.MAPPER.writeValueAsString(payload).replace("\n", " ");
            emitter.send(SseEmitter.event().name(event).data(json, org.springframework.http.MediaType.TEXT_PLAIN));
        } catch (Exception e) {
            if (closed.compareAndSet(false, true)) {
                log.debug("SSE 写出失败（客户端可能已断开）: event={} err={}", event, e.toString());
            }
        }
    }

    /** 正常收尾。即使失败也不抛，避免影响调用方。 */
    public void complete() {
        closed.set(true);
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete 失败: {}", e.toString());
        }
    }

    public boolean isClosed() {
        return closed.get();
    }
}
