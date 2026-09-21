package com.animeai.tool;

import java.time.Duration;
import java.util.List;

import com.animeai.config.AiProperties;
import com.animeai.model.AnimeCard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bangumi 客户端**打真实接口**的验收测试（不打桩）。
 *
 * <p>为什么必须打真实接口：
 * <ol>
 *   <li>这里验证「Jackson 映射 + Bangumi 字段命名」的组合。Boot 4（Jackson 3）与
 *       LangChain4j（Jackson 2）在 classpath 上并存，注解一旦被错误的运行时解释，
 *       表现是字段**静默变 null** —— 打桩测不出来。实测就踩到了 {@code rating.count}
 *       在真实响应里是「分数分布对象」而非整数。</li>
 *   <li>{@code search} 与 {@code browse} 两个接口的行为差异（标题匹配 vs 榜单浏览）
 *       是本项目工具设计的依据，只能靠真实响应确认。</li>
 * </ol>
 *
 * <p>需要联网；离线时本类会失败，这是有意的（宁可显式失败也不要静默跳过）。
 */
class BangumiClientTest {

    private static final String UA = "anime-ai-test/0.1 (https://github.com/Amenoe/anime-chat)";

    private BangumiClient newClient() {
        AiProperties props = new AiProperties(
                null,
                null,
                new AiProperties.Bangumi("https://api.bgm.tv", UA, Duration.ofSeconds(25)),
                null);
        return new BangumiClient(props);
    }

    @Test
    void 标题搜索应命中且中文字段正确映射() {
        List<AnimeCard> cards = newClient()
                .searchSubjects("命运石之门", null, null, null, null, "match", 5, 0);

        assertThat(cards).isNotEmpty();

        AnimeCard first = cards.get(0);
        assertThat(first.id()).isPositive();
        // 关键断言：snake_case 的 name_cn 必须映射到 nameCn，否则说明注解没生效
        assertThat(first.nameCn()).isEqualTo("命运石之门");
        assertThat(first.score()).isNotNull();
        assertThat(first.tags()).isNotEmpty();
        assertThat(first.cover()).isNotBlank();

        System.out.println("[bangumi.search] " + first.id() + " / " + first.nameCn()
                + " / 评分=" + first.score() + " / 标签=" + first.tags());
    }

    @Test
    void 榜单浏览应返回真正的经典高分作品() {
        List<AnimeCard> cards = newClient().browseSubjects(null, null, "rank", 8, 0);

        assertThat(cards).hasSize(8);
        // 排名榜的语义：第 1 页应当全是高排名条目
        assertThat(cards).allSatisfy(card -> {
            assertThat(card.nameCn()).isNotBlank();
            assertThat(card.score()).isNotNull().isGreaterThan(8.0);
            assertThat(card.rank()).isNotNull().isLessThan(50);
        });
        // 排名应单调
        assertThat(cards.get(0).rank()).isLessThan(cards.get(cards.size() - 1).rank());

        System.out.println("[bangumi.browse 总榜] " + cards.stream()
                .map(c -> c.nameCn() + "(" + c.score() + "/#" + c.rank() + ")").toList());
    }

    @Test
    void 按年份浏览应限定在该年() {
        List<AnimeCard> cards = newClient().browseSubjects(2023, null, "rank", 8, 0);

        assertThat(cards).isNotEmpty();
        assertThat(cards).allSatisfy(card -> assertThat(card.date()).startsWith("2023"));

        System.out.println("[bangumi.browse 2023] " + cards.stream()
                .map(c -> c.nameCn() + "(" + c.score() + ")").toList());
    }

    @Test
    void 年份加月份应返回该季度番剧() {
        List<AnimeCard> cards = newClient().browseSubjects(2023, 10, "rank", 8, 0);

        assertThat(cards).isNotEmpty();
        assertThat(cards).allSatisfy(card -> assertThat(card.date()).startsWith("2023-10"));

        System.out.println("[bangumi.browse 2023-10] " + cards.stream()
                .map(c -> c.nameCn() + "(" + c.score() + ")").toList());
    }
}
