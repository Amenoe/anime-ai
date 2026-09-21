package com.animeai.chat;

import com.animeai.chat.dto.ChatRequest;
import com.animeai.config.AiProperties;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 内部 AI 接口，仅供 anime-chat-server（NestJS）转发调用，不对外暴露。
 *
 * <p>之所以是 POST + SSE 而不是 GET：前端用 fetch + ReadableStream 手写解析，
 * 请求体里要带 conversationId 与用户消息；GET 只能塞查询串，既难读也有长度限制。
 */
@RestController
@RequestMapping("/internal/ai")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final ChatService chatService;
    private final AiProperties props;

    public ChatController(ChatService chatService, AiProperties props) {
        this.chatService = chatService;
        this.props = props;
    }

    @PostConstruct
    void warnIfTokenMissing() {
        if (!StringUtils.hasText(props.internalToken())) {
            log.warn("ai.internal-token 未配置 —— 内部接口未做鉴权，仅可用于本地开发");
        }
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody ChatRequest request) {

        assertInternalToken(token);
        if (request.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages 与 message 不能同时为空");
        }
        return chatService.stream(request);
    }

    /** 供 NestJS / 运维探测存活。 */
    @PostMapping(value = "/ping", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object ping(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token) {
        assertInternalToken(token);
        return java.util.Map.of(
                "service", "anime-ai",
                "model", props.chat().model(),
                "status", "ok");
    }

    /**
     * 服务间鉴权。
     *
     * <p>注意每个入口都要调用 —— 曾经只给 {@code /chat} 加了校验、漏了 {@code /ping}，
     * 结果探测接口在配了令牌的情况下依然匿名可用。新增入口时别忘了这一行。
     */
    private void assertInternalToken(String token) {
        if (StringUtils.hasText(props.internalToken()) && !props.internalToken().equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部令牌无效");
        }
    }
}
