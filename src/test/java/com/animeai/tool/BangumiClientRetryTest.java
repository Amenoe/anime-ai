package com.animeai.tool;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.animeai.config.AiProperties;
import com.animeai.model.AnimeCard;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反代链路的**重试语义**测试（本地 HttpServer，不联网）。
 *
 * <p>生产走 Cloudflare 反代，跨境链路会同时出现两类瞬时故障：
 * <ol>
 *   <li>5xx / 连接被重置；</li>
 *   <li>200 但响应体是 HTML 错误页（反代边缘的兜底页），解析阶段才暴露。</li>
 * </ol>
 * 两类都必须重试一次；而 4xx（参数/鉴权）重试没有意义，只会拖慢响应。
 *
 * <p>这里断言的是「调用了几次上游」而不是日志，避免测试依赖日志格式。
 */
class BangumiClientRetryTest {

    private static final String OK_BODY =
            "{\"data\":[{\"id\":1,\"name\":\"A\",\"name_cn\":\"A\"}],\"total\":1}";

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    private int[] statuses = {200};
    private String[] bodies = {OK_BODY};
    private String[] contentTypes = {"application/json"};

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int index = Math.min(hits.getAndIncrement(), statuses.length - 1);
            byte[] body = bodies[index].getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentTypes[index]);
            exchange.sendResponseHeaders(statuses[index], body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 上游5xx后应重试一次并成功() {
        statuses = new int[] {500, 200};
        bodies = new String[] {"upstream boom", OK_BODY};
        contentTypes = new String[] {"text/plain", "application/json"};

        List<AnimeCard> cards = newClient().browseSubjects(null, null, "rank", 1, 0);

        assertThat(hits.get()).isEqualTo(2);
        assertThat(cards).hasSize(1);
    }

    @Test
    void 返回HTML错误页时应在解析失败后重试() {
        statuses = new int[] {200, 200};
        bodies = new String[] {"<html><body>origin error</body></html>", OK_BODY};
        contentTypes = new String[] {"text/html", "application/json"};

        List<AnimeCard> cards = newClient().browseSubjects(null, null, "rank", 1, 0);

        assertThat(hits.get()).isEqualTo(2);
        assertThat(cards).hasSize(1);
    }

    @Test
    void 参数类4xx不重试() {
        statuses = new int[] {400, 200};
        bodies = new String[] {"{\"title\":\"bad request\"}", OK_BODY};
        contentTypes = new String[] {"application/json", "application/json"};

        List<AnimeCard> cards = newClient().browseSubjects(null, null, "rank", 1, 0);

        assertThat(hits.get()).isEqualTo(1);
        assertThat(cards).isEmpty();
    }

    private BangumiClient newClient() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AiProperties props = new AiProperties(
                null,
                null,
                new AiProperties.Bangumi(baseUrl, "anime-ai-test/0.1", Duration.ofSeconds(5)),
                null,
                null,
                null,
                null,
                null);
        return new BangumiClient(props);
    }
}
