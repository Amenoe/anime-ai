package com.animeai.tool;

import java.util.List;
import java.util.Map;

import com.animeai.config.AiProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SeriesKnowledge} 的行为测试。
 *
 * <p>为什么必须测**变体匹配**：TMDB 的简体中文译文在同一季里会摇摆 ——
 * 《无职转生》第 1 季 ep5-7 写「艾莉丝」、ep8-9 写「爱丽丝」（实测）。
 * 不做变体就会漏掉后两集，而漏集是**静默的**：用户看到的结果看起来"正常"，
 * 只是少了几集，没人会发现。这类缺陷只能靠断言守住。
 *
 * <p>手法：覆盖 {@link TmdbClient} 的方法，不碰网络。
 */
class SeriesKnowledgeTest {

    private static AiProperties props() {
        return new AiProperties(
                null, null, null, null, null,
                new AiProperties.Tmdb("https://api.themoviedb.org/3", "fake-token", "zh-CN"),
                new AiProperties.Retrieval("hybrid", true),
                "token");
    }

    /** 构造一个返回固定数据的 TMDB 客户端。 */
    private static TmdbClient fakeTmdb(List<TmdbClient.Episode> episodes) {
        TmdbClient.SeriesHit hit = new TmdbClient.SeriesHit(1, "无职转生", "Mushoku Tensei", "2021-01-11", "");
        TmdbClient.SeriesDetail detail = new TmdbClient.SeriesDetail(
                1, "无职转生", "Mushoku Tensei",
                List.of(
                        new TmdbClient.Season(0, "特别篇", 3, "2021-01-01"),
                        new TmdbClient.Season(1, "第 1 季", episodes.size(), "2021-01-11"),
                        new TmdbClient.Season(2, "第 2 季", 2, "2023-07-03")));
        return new TmdbClient(props()) {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public List<SeriesHit> searchSeries(String query, int limit) {
                return query.contains("无职") ? List.of(hit) : List.of();
            }

            @Override
            public SeriesDetail seriesDetail(int seriesId) {
                return detail;
            }

            @Override
            public List<Episode> season(int seriesId, int seasonNumber) {
                if (seasonNumber == 1) return episodes;
                if (seasonNumber == 2) return List.of(
                        new TmdbClient.Episode(1, "第二季第一集", "鲁迪乌斯与艾莉丝重逢。", "2023-07-03"),
                        new TmdbClient.Episode(2, "第二季第二集", "新的冒险开始。", "2023-07-10"));
                return List.of();
            }
        };
    }

    private static List<TmdbClient.Episode> season1() {
        return List.of(
                new TmdbClient.Episode(1, "无职转生", "34岁家里蹲转生到异世界。", "2021-01-11"),
                new TmdbClient.Episode(5, "大小姐与暴力", "准备前去担任伯雷亚斯家的大小姐——艾莉丝的家教。", "2021-02-08"),
                new TmdbClient.Episode(8, "转折点1", "爱丽丝和她的家人为他安排了一个惊喜派对。", "2021-03-01"),
                new TmdbClient.Episode(12, "拥有魔眼的女人", "遇到了饿肚子的谜之少女。", "2021-10-04"));
    }

    // ── 变体生成（纯函数，最该测）────────────────────────────

    @Test
    void 变体包含同音字写法() {
        var variants = SeriesKnowledge.variantsOf("艾莉丝");
        assertThat(variants).contains("艾莉丝");
        // 这三个是 TMDB 实际出现过的写法
        assertThat(variants).as("必须覆盖「爱丽丝」，否则第 8/9 集会漏")
                .contains("爱丽丝");
    }

    @Test
    void 变体数量有上限不会爆炸() {
        // 4 个都可替换的字，朴素组合是 3^4 = 81 种，必须被截断
        var variants = SeriesKnowledge.variantsOf("艾莉丝德");
        assertThat(variants.size()).isLessThanOrEqualTo(12);
    }

    @Test
    void 只有一个可替换字时只多一个变体() {
        // 「鲁迪乌斯」里只有「斯」在同音表（→丝），所以恰好两个变体。
        // 这条断言的意图是「不要无脑展开」：3 个字里有 1 个可替换 → 2 个变体，不是 2^3。
        var variants = SeriesKnowledge.variantsOf("鲁迪乌斯");
        assertThat(variants).contains("鲁迪乌斯").contains("鲁迪乌丝");
        assertThat(variants).hasSize(2);
    }

    // ── 角色集数 ─────────────────────────────────────────────

    @Test
    void 同一季里的两种译名都能命中() {
        SeriesKnowledge knowledge = new SeriesKnowledge(fakeTmdb(season1()));

        String result = knowledge.findCharacterEpisodes("无职转生", "艾莉丝", 1);

        // ep5 写「艾莉丝」、ep8 写「爱丽丝」，必须都命中
        assertThat(result).contains("5").contains("8");
        assertThat(result).as("要给出依据说明，避免断言成画面出场")
                .contains("不等于").contains("画面出场");
    }

    @Test
    void 搜不到作品时给出可执行的下一步() {
        SeriesKnowledge knowledge = new SeriesKnowledge(fakeTmdb(season1()));

        String result = knowledge.findCharacterEpisodes("不存在的番", "某人", 1);

        assertThat(result).contains("没有找到").contains("search_anime");
    }

    @Test
    void 一集都没匹配到时如实说明而不是编造() {
        SeriesKnowledge knowledge = new SeriesKnowledge(fakeTmdb(season1()));

        String result = knowledge.findCharacterEpisodes("无职转生", "查无此人", 1);

        assertThat(result).contains("一集都没匹配到");
        assertThat(result).contains("简介没写");
    }

    // ── 分季大纲 ─────────────────────────────────────────────

    @Test
    void 指定单季时只返回那一季() {
        SeriesKnowledge knowledge = new SeriesKnowledge(fakeTmdb(season1()));

        String result = knowledge.seriesOutline("无职转生", 2, null);

        assertThat(result).contains("【第 2 季】");
        assertThat(result).as("指定 season=2 时不该把第 1 季也拉进来").doesNotContain("【第 1 季】");
    }

    @Test
    void 不指定季时按_seasons_取前几季且跳过特别篇() {
        SeriesKnowledge knowledge = new SeriesKnowledge(fakeTmdb(season1()));

        String result = knowledge.seriesOutline("无职转生", null, 2);

        assertThat(result).contains("【第 1 季】").contains("【第 2 季】");
        assertThat(result).as("season 0 是特别篇，与「第几季」直觉不符，应跳过")
                .doesNotContain("【第 0 季】");
    }

    @Test
    void 指定不存在的季时返回现有季列表() {
        SeriesKnowledge knowledge = new SeriesKnowledge(fakeTmdb(season1()));

        String result = knowledge.seriesOutline("无职转生", 9, null);

        assertThat(result).contains("没有第 9 季");
        assertThat(result).as("要告诉模型有哪些季可选，它才能自我纠正").contains("[1, 2]");
    }

    @Test
    void 未配置TMDB时明确说明而不是空结果() {
        TmdbClient disabled = new TmdbClient(props()) {
            @Override
            public boolean enabled() {
                return false;
            }
        };
        SeriesKnowledge knowledge = new SeriesKnowledge(disabled);

        assertThat(knowledge.seriesOutline("无职转生", null, null)).contains("未配置 TMDB");
        assertThat(knowledge.findCharacterEpisodes("无职转生", "艾莉丝", 1)).contains("未配置 TMDB");
    }

    @Test
    void 多季叠加时触发总预算截断() {
        // 单季 40 集时自适应会把每集压到 60 字（总量约 2800），预算根本用不到；
        // 两季 80 集也只有约 5600，还是不超。**三季 120 集**才必然撞上 6000 预算 ——
        // 这也说明：预算兜底主要是防「一次性拉很多季」，而不是防单季集数多。
        List<TmdbClient.Episode> many = java.util.stream.IntStream.rangeClosed(1, 40)
                .mapToObj(i -> new TmdbClient.Episode(i, "第" + i + "集", "剧情".repeat(150), "2021-01-01"))
                .toList();
        TmdbClient fake = new TmdbClient(props()) {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public List<SeriesHit> searchSeries(String query, int limit) {
                return List.of(new SeriesHit(1, "无职转生", "Mushoku Tensei", "2021-01-11", ""));
            }

            @Override
            public SeriesDetail seriesDetail(int seriesId) {
                return new SeriesDetail(1, "无职转生", "Mushoku Tensei", List.of(
                        new Season(1, "第 1 季", 40, "2021-01-11"),
                        new Season(2, "第 2 季", 40, "2023-07-03"),
                        new Season(3, "第 3 季", 40, "2026-01-05")));
            }

            @Override
            public List<Episode> season(int seriesId, int seasonNumber) {
                return many;
            }
        };
        SeriesKnowledge knowledge = new SeriesKnowledge(fake);

        String result = knowledge.seriesOutline("无职转生", null, 3);

        assertThat(result.length()).as("必须封顶在 6000 字附近").isLessThan(7000);
        assertThat(result).as("截断后要提示模型可以改用单季").contains("已达输出上限");
    }
}
