package com.animeai.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 内部接口令牌校验（`X-Internal-Token`）。
 *
 * <p><b>为什么做成拦截器而不是每个 controller 自己校验</b>：原先
 * {@code ChatController.assertInternalToken} 与 {@code KnowledgeController.assertToken}
 * 是两份**逐字相同**的私有方法。本项目已经因为这种重复踩过两次坑：
 * 一次是只给 {@code /chat} 加了校验、漏了 {@code /ping}，配了令牌后探测接口依然匿名可用；
 * 另一次是 {@code assertRoot} 在 track/ai 各抄一份，后来才抽成公共守卫。
 *
 * <p>更关键的是**新增端点时的默认行为**：controller 内校验意味着每加一个内部接口都要
 * 记得写一次，漏一次就是匿名可用；拦截器把 {@code /internal/**} 整体罩住，
 * **新接口默认是安全的**，忘记配置的代价从「泄漏」变成「用不了」——后者会立刻被发现。
 *
 * <p>校验规则刻意「配置了才校验」：本地不配 {@code AI_INTERNAL_TOKEN} 时
 * M1 仍能直接调用，便于调试。生产必须配。
 */
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    /** 内部令牌请求头名。让两个 controller 自己去写字符串，迟早会拼错或改名不同步 */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    private final AiProperties props;

    public InternalTokenInterceptor(AiProperties props) {
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String expected = props.internalToken();
        // 未配置令牌时不拦截：本地调试不需要。生产环境必须配置，见 .env.example
        if (!StringUtils.hasText(expected)) {
            return true;
        }
        String actual = request.getHeader(TOKEN_HEADER);
        if (!expected.equals(actual)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部令牌无效");
        }
        return true;
    }
}
