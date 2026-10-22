package com.animeai.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 系列级知识：分季剧情摘要、角色出现集数。
 *
 * <p>这两个能力**刻意不用向量检索**：它们要的是「按季遍历、按集匹配」这种结构化查询，
 * 而不是「给一段文本找相似文本」。用 RAG 做会引入不必要的 embedding 成本，
 * 而且答不准（一个角色名不需要语义相似，只需要字面命中）。
 *
 * <p>实测依据（《无职转生》，2026-10-23）：
 * <ul>
 *   <li>Bangumi 的 {@code episodes.desc} 覆盖率仅 17%，第一季为 0% —— 不可用</li>
 *   <li>TMDB 第一季 23/23 = 100%，第二季 24/24 = 100%，且是中文、含角色名</li>
 *   <li>「艾莉丝」在第 1 季命中 12 集、「爱丽丝」命中 2 集（8/9）——
 *       同一季里两种译名并存，所以必须做变体匹配</li>
 * </ul>
 */
@Component
public class SeriesKnowledge {

    private static final Logger log = LoggerFactory.getLogger(SeriesKnowledge.class);

    /** 简介片段截断长度：给模型看的证据，不需要整段 */
    private static final int SNIPPET = 70;
    /** 最多列出的证据条数，避免把上下文塞满 */
    private static final int MAX_EVIDENCE = 12;
    /** 默认最多看几季（防止一次拉太多触发限流） */
    private static final int DEFAULT_SEASONS = 2;
    /**
     * 单次返回的**总字符预算**（约 3000~4000 token）。
     *
     * <p>为什么必须封顶：一季 24 集 × 每集 120 字 = 近 3000 字，三季就接近 9000 字，
     * 会挤掉对话历史与工具定义。而归纳主线根本不需要逐集全文 ——
     * 给足每集 60~120 字就够模型抓剧情走向。
     */
    private static final int OUTLINE_CHAR_BUDGET = 6000;

    /**
     * 常见译名同音字。
     *
     * <p>不这么干就会漏：同一部作品里「艾莉丝」（ep5-7）和「爱丽丝」（ep8-9）
     * 并存，都是 TMDB 社区译文。这不是用户的错，是数据源自身的噪音。
     */
    private static final Map<Character, String> HOMOPHONES = Map.ofEntries(
            Map.entry('艾', "爱"), Map.entry('爱', "艾"),
            Map.entry('莉', "丽利"), Map.entry('丽', "莉利"), Map.entry('利', "莉丽"),
            Map.entry('丝', "斯"), Map.entry('斯', "丝"),
            Map.entry('露', "路"), Map.entry('路', "露"),
            Map.entry('德', "特得"), Map.entry('特', "德"),
            Map.entry('洛', "罗"), Map.entry('罗', "洛"),
            Map.entry('娜', "纳"), Map.entry('纳', "娜"));

    /** 变体数量上限：每个字最多 3 个候选，4 字名最多 81 种，太夸张就截断。 */
    private static final int MAX_VARIANTS = 12;

    private final TmdbClient tmdb;

    public SeriesKnowledge(TmdbClient tmdb) {
        this.tmdb = tmdb;
    }

    /**
     * 查某个角色在哪些集出现（依据：分集剧情简介里是否提到该名字）。
     *
     * @return 给模型读的文本；TMDB 未配置或搜不到作品时返回带原因的空结果
     */
    public String findCharacterEpisodes(String title, String character, Integer maxSeasons) {
        if (!tmdb.enabled()) {
            return "（未配置 TMDB，无法查询分集信息）";
        }
        if (!StringUtils.hasText(title) || !StringUtils.hasText(character)) {
            return "（需要同时提供作品名和角色名）";
        }
        List<TmdbClient.SeriesHit> hits = tmdb.searchSeries(title, 3);
        if (hits.isEmpty()) {
            return "没有找到作品《" + title + "》，可能译名不同。可以先用 search_anime 确认作品名。";
        }
        TmdbClient.SeriesHit hit = hits.get(0);
        TmdbClient.SeriesDetail detail = tmdb.seriesDetail(hit.id());
        if (detail == null || detail.seasons() == null) {
            return "TMDB 上没有《" + hit.name() + "》的季信息。";
        }

        int limit = maxSeasons == null || maxSeasons <= 0 ? DEFAULT_SEASONS : Math.min(maxSeasons, 6);
        // season 0 是特别篇，与「第几季」的直觉不符，默认跳过
        List<TmdbClient.Season> seasons = detail.seasons().stream()
                .filter(s -> s.seasonNumber() > 0)
                .sorted((a, b) -> Integer.compare(a.seasonNumber(), b.seasonNumber()))
                .limit(limit)
                .toList();

        Set<String> variants = variantsOf(character);
        List<String> seasonLines = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        int total = 0;

        for (TmdbClient.Season season : seasons) {
            List<Integer> eps = new ArrayList<>();
            for (TmdbClient.Episode ep : tmdb.season(hit.id(), season.seasonNumber())) {
                String overview = ep.overview() == null ? "" : ep.overview();
                String matched = matchFirst(overview, variants);
                if (matched == null) continue;
                eps.add(ep.episodeNumber());
                if (evidence.size() < MAX_EVIDENCE) {
                    evidence.add("第" + season.seasonNumber() + "季 ep" + ep.episodeNumber()
                            + "「" + safe(ep.name()) + "」：…" + snippetAround(overview, matched) + "…");
                }
            }
            total += eps.size();
            if (eps.isEmpty()) {
                seasonLines.add("第 " + season.seasonNumber() + " 季：未在简介中提到");
            } else {
                seasonLines.add("第 " + season.seasonNumber() + " 季（" + season.episodeCount() + " 集）："
                        + "命中 " + eps.size() + " 集 → " + String.join("、", eps.stream().map(String::valueOf).toList()));
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("《").append(hit.name()).append("》中「").append(character).append("」的命中情况\n");
        out.append("（依据 TMDB 每集剧情简介的文本匹配；匹配变体：")
                .append(String.join(" / ", variants)).append("）\n");
        out.append(String.join("\n", seasonLines)).append("\n");
        if (total == 0) {
            out.append("\n一集都没匹配到。可能是：① 该角色只出现在画面里而简介没写名字；")
                    .append("② 译名差异过大；③ 该角色不属于这几季。\n");
            return out.toString();
        }
        out.append("\n证据片段：\n").append(String.join("\n", evidence)).append("\n");
        out.append("\n⚠️ 这是「剧情简介提到该角色」的集数，**不等于**画面出场；")
                .append("回答时要说明依据，不要断言「只有这些集有她」。");
        return out.toString();
    }

    /**
     * 分季剧情摘要的素材：把每集的标题与简介按季整理给模型。
     *
     * <p>刻意只给**素材**，不让本方法自己总结 —— 归纳交给对话模型，
     * 它更擅长组织语言；这里只负责把数据整齐地摆出来。
     */
    public String seriesOutline(String title, Integer season, Integer maxSeasons) {
        if (!tmdb.enabled()) {
            return "（未配置 TMDB，无法查询分集信息）";
        }
        if (!StringUtils.hasText(title)) {
            return "（需要提供作品名）";
        }
        List<TmdbClient.SeriesHit> hits = tmdb.searchSeries(title, 3);
        if (hits.isEmpty()) {
            return "没有找到作品《" + title + "》，可能译名不同。可以先用 search_anime 确认作品名。";
        }
        TmdbClient.SeriesHit hit = hits.get(0);
        TmdbClient.SeriesDetail detail = tmdb.seriesDetail(hit.id());
        if (detail == null || detail.seasons() == null) {
            return "TMDB 上没有《" + hit.name() + "》的季信息。";
        }

        List<TmdbClient.Season> all = detail.seasons().stream()
                .filter(s -> s.seasonNumber() > 0) // season 0 是特别篇，与「第几季」的直觉不符
                .sorted((a, b) -> Integer.compare(a.seasonNumber(), b.seasonNumber()))
                .toList();

        List<TmdbClient.Season> picked;
        if (season != null && season > 0) {
            // 指定了单季：只取它。这样「第三季讲了什么」不会把前两季一起拉进来。
            picked = all.stream().filter(s -> s.seasonNumber() == season).toList();
            if (picked.isEmpty()) {
                String existing = all.stream().map(s -> String.valueOf(s.seasonNumber())).toList().toString();
                return "《" + hit.name() + "》没有第 " + season + " 季。现有季：" + existing;
            }
        } else {
            int n = maxSeasons == null || maxSeasons <= 0 ? DEFAULT_SEASONS : Math.min(maxSeasons, 6);
            picked = all.stream().limit(n).toList();
        }

        // 按总集数自适应每集长度：集越多给得越短，保证总量可控
        int estimated = picked.stream().mapToInt(TmdbClient.Season::episodeCount).sum();
        int perEpisode = estimated <= 15 ? 120 : estimated <= 30 ? 90 : 60;

        StringBuilder out = new StringBuilder();
        out.append("《").append(hit.name()).append("》");
        if (StringUtils.hasText(hit.firstAirDate())) out.append("（首播 ").append(hit.firstAirDate()).append("）");
        out.append("\n");

        int withOverview = 0;
        int totalEpisodes = 0;
        boolean truncated = false;

        for (TmdbClient.Season sn : picked) {
            List<TmdbClient.Episode> eps = tmdb.season(hit.id(), sn.seasonNumber());
            out.append("\n【第 ").append(sn.seasonNumber()).append(" 季】共 ")
                    .append(sn.episodeCount()).append(" 集\n");
            for (TmdbClient.Episode ep : eps) {
                String overview = ep.overview() == null ? "" : ep.overview().replaceAll("\\s+", " ").trim();
                String body = overview.isEmpty() ? "（无简介）"
                        : (overview.length() > perEpisode ? overview.substring(0, perEpisode) + "…" : overview);
                String line = "ep" + ep.episodeNumber() + "「" + safe(ep.name()) + "」：" + body + "\n";
                if (out.length() + line.length() > OUTLINE_CHAR_BUDGET) {
                    truncated = true;
                    break;
                }
                totalEpisodes++;
                if (!overview.isEmpty()) withOverview++;
                out.append(line);
            }
            if (truncated) break;
        }

        out.append("\n（列出 ").append(totalEpisodes).append(" 集，其中 ").append(withOverview).append(" 集有简介");
        if (truncated) {
            out.append("；已达输出上限，后面的集未列出 —— 如需完整内容请指定单季，例如 season=2");
        }
        out.append("。请据此归纳每一季的主线，不要逐集复述。）");
        return out.toString();
    }

    // ── 内部工具 ───────────────────────────────────────────────

    /** 生成名字的变体：直接原样 + 同音字替换组合。 */
    static Set<String> variantsOf(String name) {
        String base = name.trim();
        Set<String> out = new LinkedHashSet<>();
        out.add(base);
        List<String> frontier = new ArrayList<>(List.of(base));
        for (char ch : base.toCharArray()) {
            String alts = HOMOPHONES.get(ch);
            if (alts == null) continue;
            List<String> next = new ArrayList<>(frontier);
            for (String current : frontier) {
                for (char alt : alts.toCharArray()) {
                    String replaced = current.replace(ch, alt);
                    if (!replaced.equals(current)) next.add(replaced);
                }
            }
            frontier = next;
            if (frontier.size() > MAX_VARIANTS * 4) break;
        }
        for (String v : frontier) {
            if (out.size() >= MAX_VARIANTS) break;
            out.add(v);
        }
        return out;
    }

    private static String matchFirst(String text, Set<String> variants) {
        if (text == null || text.isEmpty()) return null;
        for (String v : variants) {
            if (text.contains(v)) return v;
        }
        return null;
    }

    /** 取命中词前后的片段，作为给模型看的证据。 */
    private static String snippetAround(String text, String needle) {
        int index = text.indexOf(needle);
        if (index < 0) return text.length() > SNIPPET ? text.substring(0, SNIPPET) : text;
        int start = Math.max(0, index - SNIPPET / 2);
        int end = Math.min(text.length(), start + SNIPPET);
        String snippet = text.substring(start, end).replaceAll("\\s+", " ").trim();
        return (start > 0 ? "…" : "") + snippet + (end < text.length() ? "…" : "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
