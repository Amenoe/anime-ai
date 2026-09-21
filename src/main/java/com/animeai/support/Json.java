package com.animeai.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 共享的 Jackson 2 实例。
 *
 * <p><b>为什么不用注入的 ObjectMapper？</b>
 * Spring Boot 4 默认改用 <b>Jackson 3</b>（{@code tools.jackson.core:jackson-databind:3.x}），
 * 而 LangChain4j 1.20.0 仍构建在 <b>Jackson 2</b>（{@code com.fasterxml.jackson.core:2.21.x}）之上 ——
 * 两者在 classpath 上并存。于是：
 * <ul>
 *   <li>Boot 4 <b>不再</b>自动配置 {@code com.fasterxml.jackson.databind.ObjectMapper} bean
 *       （注入它会直接启动失败）；</li>
 *   <li>若把 DTO 的解析交给 Spring MVC / RestClient 的消息转换器，到底由哪个 Jackson 解释
 *       {@code @JsonProperty} 就成了隐式行为，版本冲突时表现为「字段静默变 null」，很难查。</li>
 * </ul>
 * 所以这里显式持有 Jackson 2 实例（与 LangChain4j 同一运行时），
 * Bangumi 响应解析与 SSE 序列化都走它，行为完全确定。
 *
 * <p>Spring MVC 自己的请求/响应转换仍交给 Boot 4 的 Jackson 3 —— 我们的 REST DTO
 * 不带任何 Jackson 注解，两边互不影响。
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {}
}
