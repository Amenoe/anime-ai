package com.animeai.tool;

import java.util.List;

import com.animeai.model.AnimeCard;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 暴露给大模型的番剧检索工具。
 *
 * <p>设计原则（见 docs/ai-rag-practice-roadmap.md §2）：
 * 「找番」这类**结构化**查询走 Bangumi API，不需要向量检索。
 * RAG 只用来解决 API 覆盖不到的问题（模糊回忆、台词定位、站内记录）。
 */
@Component
public class AnimeTools {

    private static final Logger log = LoggerFactory.getLogger(AnimeTools.class);

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;
    /** keyword 为空时的兜底：Bangumi 空串关键词会 400，必须给一个非空值 */
    private static final String FALLBACK_KEYWORD = "动画";

    private final BangumiClient bangumi;

    public AnimeTools(BangumiClient bangumi) {
        this.bangumi = bangumi;
    }

    @Tool(name = "search_anime", value = """
            按**标题关键词**检索番剧（数据来自 Bangumi）。
            适用场景：用户说出了番剧名字（或名字的一部分），想找到它/相关作品。
            不适用场景：用户想「按题材/标签发现番剧」——本接口的 keyword 是标题字面匹配，
            不含语义与标签召回，用题材当关键词会返回大量无关条目。这种情况请改用 browse_anime。
            keyword 必填非空；tags/metaTags 只作为附加过滤，且必须与标题关键词同时成立才会收窄结果。
            """)
    public List<AnimeCard> searchAnime(
            @P("检索关键词，必填非空，应为番剧标题或其中一部分。例如「命运石之门」「进击的巨人」") String keyword,
            @P(value = "中文标签，如 科幻、悬疑、治愈。作为附加过滤，可省略", required = false) List<String> tags,
            @P(value = "公共标签，如 TV、WEB、OVA、剧场版、原创、漫画改。作为附加过滤，可省略", required = false) List<String> metaTags,
            @P(value = "最低评分（含），如 8.0。作为附加过滤，可省略", required = false) Double minRating,
            @P(value = "最早放送年份（含），如 2020。作为附加过滤，可省略", required = false) Integer yearFrom,
            @P(value = "排序：rank=排名 heat=热度 score=评分 match=匹配度。默认 match", required = false) String sort,
            @P(value = "返回条数，默认 8，最多 20", required = false) Integer limit) {

        String effectiveKeyword = resolveKeyword(keyword);
        int effectiveLimit = normalizeLimit(limit);

        log.debug(
                "searchAnime keyword={} tags={} metaTags={} minRating={} yearFrom={} sort={} limit={}",
                effectiveKeyword, tags, metaTags, minRating, yearFrom, sort, effectiveLimit);

        List<AnimeCard> cards = bangumi.searchSubjects(
                effectiveKeyword, tags, metaTags, minRating, yearFrom, sort, effectiveLimit, 0);
        log.debug("searchAnime 命中 {} 条", cards.size());
        return cards;
    }

    @Tool(name = "browse_anime", value = """
            按年份/季度浏览高质量番剧（数据来自 Bangumi 的排名榜）。
            这是「推荐番剧」的首选工具：sort=rank 返回的是真正的经典高分作品，
            配合 year（可加 month）可以得到「2023 年」「2023 年 10 月」这类季度榜单。
            用户说「推荐几部高分番」「2023 年有什么好看的」「上季新番」时都应该用它。
            返回值已按排名从高到低，直接取前几部推荐即可。
            """)
    public List<AnimeCard> browseAnime(
            @P(value = "放送年份，如 2023。可省略，省略则返回历史总榜", required = false) Integer year,
            @P(value = "放送月份 1-12，如 10 表示十月番。仅在给了 year 时生效，可省略", required = false) Integer month,
            @P(value = "排序：rank=按排名（推荐）/ date=按放送日期。默认 rank", required = false) String sort,
            @P(value = "返回条数，默认 8，最多 20", required = false) Integer limit) {

        int effectiveLimit = normalizeLimit(limit);
        Integer effectiveMonth = (year == null) ? null : month;

        log.debug("browseAnime year={} month={} sort={} limit={}", year, effectiveMonth, sort, effectiveLimit);

        List<AnimeCard> cards = bangumi.browseSubjects(year, effectiveMonth, sort, effectiveLimit, 0);
        log.debug("browseAnime 命中 {} 条", cards.size());
        return cards;
    }

    private static int normalizeLimit(Integer limit) {
        return (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    /**
     * 保证 keyword 非空。
     *
     * <p>Bangumi 的搜索接口对空 keyword 直接返回 400，而模型很容易生成空串
     * （例如用户只说「推荐点动漫」时）。这里兜底成宽泛词，避免整轮失败。
     */
    private static String resolveKeyword(String keyword) {
        return (keyword == null || keyword.isBlank()) ? FALLBACK_KEYWORD : keyword.trim();
    }
}
