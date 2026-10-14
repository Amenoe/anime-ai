package com.animeai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配。
 *
 * <p>把 {@link InternalTokenInterceptor} 挂到 `/internal/**`：**整个内部命名空间统一受保护**，
 * 而不是让每个 controller 自己记得校验。新增内部端点时默认是安全的，
 * 忘记配置的代价从「匿名可用」变成「调不通」—— 后者会立刻被发现。
 *
 * <p>这里刻意不拦截业务前缀（如果将来有对外开放的接口），只罩内部接口。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final InternalTokenInterceptor internalTokenInterceptor;

    public WebConfig(InternalTokenInterceptor internalTokenInterceptor) {
        this.internalTokenInterceptor = internalTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalTokenInterceptor).addPathPatterns("/internal/**");
    }
}
