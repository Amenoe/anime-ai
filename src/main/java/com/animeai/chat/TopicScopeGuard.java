package com.animeai.chat;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 话题范围守卫：在**调用模型之前**判断用户输入是否属于「动漫」范畴。
 *
 * <h2>为什么要前置拦截</h2>
 * 这是一个只做番剧推荐/检索的助手。用户问「帮我写个快速排序」时，模型要么浪费一次
 * 完整生成、要么给出一段与产品定位无关的回答 —— 前者费 token，后者砸招牌。
 * 因此把「明显跑题」的判断放在**建连之前**：命中就直接用一条固定文案回绝，
 * 全程 0 token。
 *
 * <h2>判定策略：白名单优先 + 黑名单兜底（不是敏感词匹配）</h2>
 * <ol>
 *   <li><b>越权/注入优先</b>：这类模式本身就是信号，与话题无关，且**不受任何豁免**。</li>
 *   <li><b>白名单优先</b>：只要出现动漫载体/元数据词，就放行 —— 即使同时命中黑名单。
 *       「推荐几部关于天气的番」里的「天气」不该让它被拒。</li>
 *   <li><b>黑名单兜底</b>：命中越界领域词**且完全没有动漫信号**时才拒答。</li>
 *   <li><b>兜底放行</b>：其余一律放行（详见 {@link #judge} 第 4 步的说明）。</li>
 * </ol>
 * 也就是说：**拒答需要「正面证据」，放行不需要。** 这是刻意的非对称 ——
 * 误伤一个正常动漫提问（用户会直接走掉）远比漏放一个跑题提问（浪费一次生成）严重。
 *
 * <h2>本类为什么是纯静态、且看不到历史</h2>
 * {@link #judge} 只接收「本轮用户输入」这一个参数。历史确实能提供语境，但把它拉进来会让规则
 * 不再可判定：任何跑题请求都能靠「上一轮聊过番」混过去，而且单测必须构造历史才能覆盖。
 * 语境缺失的问题由「兜底放行」解决（见 {@link #judge}），不需要读历史。
 */
public final class TopicScopeGuard {

    /** 判定结论的原因。既用于日志观测，也让单测能断言「为什么放行/拒答」。 */
    public enum Reason {
        /** 空输入：交给上游的参数校验去报 400，这里不越权决定 */
        EMPTY,
        /** 命中动漫信号 → 放行 */
        ANIME_SIGNAL,
        /** 无越界证据 → 放行（「还有吗」「继续」这类省略语境的追问都走这里） */
        NO_SIGNAL,
        /** 疑似提示词注入 / 要求越权 → 拒答 */
        INJECTION_ATTEMPT,
        /** 打探模型身份或供应商 → 拒答 */
        IDENTITY_PROBE,
        /** 命中越界领域词且无动漫信号 → 拒答 */
        OUT_OF_SCOPE
    }

    /** 判定结果。{@code allowed=true} 表示放行给模型，否则回绝。 */
    public record Decision(boolean allowed, Reason reason) {

        static Decision allow(Reason reason) {
            return new Decision(true, reason);
        }

        static Decision reject(Reason reason) {
            return new Decision(false, reason);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 白名单：动漫信号
    //
    // 刻意**只收「动漫载体词 / 动漫专有元数据词」**，不收「恋爱、日常、运动、美食、
    // 战争」这类泛题材词。原因：泛题材词与越界话题大量重叠（美食—菜谱、运动—健身、
    // 日常—日报），一旦进白名单，「推荐几个美食菜谱」这种明确跑题的请求就会被放行。
    //
    // 而且题材词**不需要**白名单兜底：带题材的正常提问要么同时带载体词（「…的番」），
    // 要么完全不含越界词（「有没有治愈系的」），这两条路径本来就已经放行了。
    //
    // 改这里的判据只有一个：**「这个词出现了，能不能作为『在聊动漫』的正面证据」**。
    // ──────────────────────────────────────────────────────────────
    private static final List<Pattern> ANIME_SIGNALS = List.of(
                    // 载体与看番行为
                    "番", "番剧", "动漫", "动画", "漫画", "新番", "老番", "补番", "追番",
                    "看番", "番外", "泡面番", "剧场版", "ova", "oad", "轻小说",
                    // 动漫专有元数据
                    "声优", "角色", "片头", "片尾", "主题曲", "bgm", "ost",
                    // 圈子词
                    "二次元", "同人", "手办", "漫展", "cosplay", "galgame", "bangumi")
            .stream()
            .map(TopicScopeGuard::termPattern)
            .toList();

    /** 序号类说法：「第二季」「第 3 集」「第三部呢」—— 省略语境时最常见的载体指代 */
    private static final Pattern ANIME_ORDINAL =
            Pattern.compile("第[0-9一二三四五六七八九十百]+[季集话部]");

    /**
     * 「番」的**非动漫同形词**。
     *
     * <p>「番」是个高危的单字信号：番茄、番薯、番禺里都有它。放行方向误判（把
     * 「番茄炒蛋的菜谱」当成动漫问题）比拒答方向更隐蔽，所以匹配白名单前先把这些词剔掉。
     */
    private static final List<String> FALSE_FRIENDS =
            List.of("番茄", "番薯", "番瓜", "番石榴", "番禺", "番号");

    // ──────────────────────────────────────────────────────────────
    // 越权 / 提示词注入：这类模式本身即是信号，与话题无关
    // ──────────────────────────────────────────────────────────────
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // 1) 要求无视 / 覆盖既有指令、规则、设定
            Pattern.compile("(忽略|忽视|无视|忘记|忘掉|抛开|跳过|不要管|别管|推翻|覆盖|重置)"
                    + ".{0,8}"
                    + "(之前|以上|上面|上述|前面|前述|原有|所有|全部|你的)?"
                    + ".{0,4}"
                    + "(指令|规则|设定|设置|要求|提示|提示词|限制|约束|身份)"),
            // 2) 直接宣告新身份 / 要求扮演
            Pattern.compile("(你现在是|你现在就是|从现在开始你是|从现在起你是|从今以后你是"
                    + "|假装你是|假装你是一个|扮演一个|你来当)"),
            // 3) 打探或试图覆盖系统提示词
            Pattern.compile("(系统提示词|系统提示|systemprompt|你的提示词|你的设定|你的规则"
                    + "|你的指令|原始指令)"),
            // 4) 越狱口令
            Pattern.compile("(开发者模式|devmode|越狱|jailbreak|dan模式|无限制模式|不受限制)"),
            // 5) 明确要求不要遵守规则
            Pattern.compile("(不要|别|无需|不用)(再)?(遵守|遵循|按照|执行|理会)"
                    + "(之前|以上|上面)?(的)?(规则|指令|设定|要求)"));

    // ──────────────────────────────────────────────────────────────
    // 身份探询：与产品定位无关，且答了容易瞎编，一并回绝
    // ──────────────────────────────────────────────────────────────
    /** 身份类问句常见的礼貌/衔接前缀，如「请问你是 GPT 吗」 */
    private static final String ASK_PREFIX = "^(请问|请|你好|您好|哈喽|那个|所以|那么|我就想问|我想知道)?";

    private static final List<Pattern> IDENTITY_PATTERNS = List.of(
            // 直接点名别家模型 / 供应商。不锚定：这类专有名词出现在动漫提问里的概率可忽略，
            // 而「我想知道你是不是 GPT」这种不以下面几个句式开头的问法只能靠它兜住。
            Pattern.compile("(gpt|chatgpt|claude|gemini|文心一言|通义千问|kimi|豆包|deepseek|openai)"),
            // 「你是什么模型」「你用的哪个大模型」
            Pattern.compile(ASK_PREFIX
                    + "(你|您)(到底|究竟)?(是|用的|使用的|基于).{0,4}(什么|哪个|哪种|哪家).{0,4}"
                    + "(模型|大模型|ai|人工智能|引擎)"),
            // 「你是 AI 吗」「你是不是机器人」
            //
            // ⚠️ 必须锚定句首：不锚定的话「你觉得这部番是 AI 画的吗」会被
            //    `你.{0,4}是.{0,2}ai` 命中 —— 那是一个完全正常的动漫提问（误伤）。
            //    句尾也收紧了（吗/么/？/结尾），否则「…是不是 ai 画风」同样会中招。
            Pattern.compile(ASK_PREFIX
                    + "(你|您)(是|是不是|不是).{0,2}(ai|人工智能|机器人|真人|人类)(吗|么|？|\\?|$)"),
            // 「你是谁」「你谁啊」
            Pattern.compile(ASK_PREFIX + "(你|您)(是|到底)?谁"),
            // 「底层模型」「什么大模型」—— 无歧义，不锚定
            Pattern.compile("(底层模型|什么大模型|哪个大模型)"));

    // ──────────────────────────────────────────────────────────────
    // 黑名单：明确的越界领域词
    //
    // 只在**没有任何动漫信号**时才生效，所以可以写得宽 —— 写宽的代价只是漏放，
    // 而不是误伤。分组只为可读性，判定时一视同仁。
    // ──────────────────────────────────────────────────────────────
    private static final List<Pattern> OFF_TOPIC_PATTERNS = List.of(
                    // 编程与计算机
                    "写代码", "代码", "编程", "程序员", "写个程序", "写程序", "程序", "函数",
                    "变量", "数组", "链表", "指针", "递归", "排序", "算法", "编译", "报错",
                    "调试", "bug", "正则", "数据库", "sql", "mysql", "redis", "爬虫", "脚本",
                    "shell", "docker", "kubernetes", "部署", "服务器", "nginx", "git",
                    "java", "python", "javascript", "typescript", "golang", "rust", "c++",
                    "html", "css", "react", "vue", "spring", "leetcode", "力扣", "单元测试",
                    // 天气与生活服务
                    "天气", "气温", "下雨", "降雨", "台风", "空气质量", "雾霾", "股票", "股市",
                    "基金", "彩票", "汇率", "房贷", "信用卡", "航班", "机票", "火车票", "高铁",
                    "酒店", "外卖", "快递", "菜谱", "食谱", "做饭", "减肥", "健身", "瑜伽",
                    "医院", "挂号", "体检", "吃药", "用药", "什么药", "打车", "导航", "旅游攻略",
                    "租房", "买房",
                    // 学业与写作
                    //
                    // ⚠️ 这里刻意**不收裸「翻译」**：它是唯一一个会误伤正常动漫提问的候选 ——
                    //    「翻译一下命运石之门」是合理的动漫请求，但它不含任何载体词，
                    //    裸「翻译」会把这条正常提问拒掉。改成只匹配「翻译整段文本」的措辞，
                    //    漏下的由系统提示词那层兜底（分层防御的意义就在这）。
                    "作文", "论文", "写一篇", "读后感", "润色", "简历", "写邮件", "邮件",
                    "翻译这段", "翻译一下这段", "翻译下面", "翻译以下", "翻译成英文",
                    "翻译成日文", "翻译成中文",
                    "ppt", "excel", "作业", "考试题", "微积分", "导数", "积分",
                    "证明题", "物理题", "化学题", "英语题", "高数", "阅读理解", "完形填空",
                    // 纯生成任务
                    "写首诗", "写诗", "首诗", "讲个笑话", "笑话", "起个名", "取名",
                    "写文案", "写方案", "写公文", "写总结", "写报告", "对联", "藏头诗",
                    // 专业建议
                    "诊断", "症状", "法律咨询", "投资建议")
            .stream()
            .map(TopicScopeGuard::termPattern)
            .toList();

    /**
     * 字面词表放不下的变体：中文会在动词和名词之间插量词
     * （「解方程」→「解个方程」、「写作业」→「写个作业」），裸字面串会漏掉。
     *
     * <p>单测实测到的：{@code 帮我解个方程} 用字面「解方程」匹配不到，直接漏放。
     */
    private static final List<Pattern> OFF_TOPIC_REGEX = List.of(
            Pattern.compile("解.{0,2}方程"),
            Pattern.compile("(写|做|抄).{0,2}作业"),
            Pattern.compile("(写|做|改).{0,2}简历"));

    private TopicScopeGuard() {}

    /**
     * 判定本轮用户输入是否放行。
     *
     * <p>四步，顺序不能换：
     * <ol>
     *   <li><b>越权/注入</b> —— 必须在白名单**之前**。这类模式本身就是拒答依据，
     *       「忽略之前的指令」只有 7 个字、也不含任何动漫词，靠白名单/黑名单都拦不住。</li>
     *   <li><b>身份探询</b> —— 同上，独立成类。</li>
     *   <li><b>白名单优先</b> —— 有动漫信号就放行，哪怕同时命中黑名单。</li>
     *   <li><b>黑名单兜底 + 放行</b> —— 只有「命中越界词且无动漫信号」才拒答。</li>
     * </ol>
     *
     * <p><b>关于「短句一律放行」</b>：这里**刻意不做按长度豁免**。
     * 「还有吗」「继续」「第三部呢」这类省略语境的追问确实必须放行，但它们本来就
     * 不含越界词，走的是第 4 步的兜底放行 —— 动漫上下文可能只在历史里，而本方法看不到历史，
     * 所以「没有越界证据」就已经足够放行。
     *
     * <p>反过来，短句同样可能是**明确越界**的：「今天天气怎么样」只有 7 个字。
     * 若加一条「长度 ≤ N 一律放行」，它就会被放进来。所以短句的豁免只体现在
     * **「不需要命中白名单」**，而不是「不受黑名单约束」。
     */
    public static Decision judge(String rawPrompt) {
        String text = normalize(rawPrompt);
        if (text.isEmpty()) {
            return Decision.allow(Reason.EMPTY);
        }

        // 1) 越权 / 注入：与话题无关，且不受白名单豁免
        if (matchesAny(INJECTION_PATTERNS, text)) {
            return Decision.reject(Reason.INJECTION_ATTEMPT);
        }

        // 2) 身份探询
        if (matchesAny(IDENTITY_PATTERNS, text)) {
            return Decision.reject(Reason.IDENTITY_PROBE);
        }

        // 3) 白名单优先：有动漫信号即放行，即使命中黑名单
        String scoped = stripFalseFriends(text);
        if (matchesAny(ANIME_SIGNALS, scoped) || ANIME_ORDINAL.matcher(scoped).find()) {
            return Decision.allow(Reason.ANIME_SIGNAL);
        }

        // 4) 黑名单兜底：明确越界且完全无动漫信号 → 拒答
        if (matchesAny(OFF_TOPIC_PATTERNS, text) || matchesAny(OFF_TOPIC_REGEX, text)) {
            return Decision.reject(Reason.OUT_OF_SCOPE);
        }

        // 5) 兜底放行：没有越界证据就是放行。非对称是刻意的 ——
        //    漏放只是浪费一次生成，误伤会让用户直接走掉。
        return Decision.allow(Reason.NO_SIGNAL);
    }

    // ──────────────────────────────────────────────────────────────
    // 匹配工具
    // ──────────────────────────────────────────────────────────────

    /**
     * 把词条编译成匹配模式。
     *
     * <p>中文词直接当子串匹配；**纯 ASCII 词必须加词边界** —— 否则
     * {@code react} 会命中 {@code reaction}、{@code ed} 会命中 {@code code}。
     */
    private static Pattern termPattern(String term) {
        String quoted = Pattern.quote(term);
        boolean ascii = term.chars().allMatch(c -> c < 128);
        return ascii
                ? Pattern.compile("(?<![a-z0-9])" + quoted + "(?![a-z0-9])")
                : Pattern.compile(quoted);
    }

    private static boolean matchesAny(List<Pattern> patterns, String text) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一化：全角折半角、转小写、**去掉所有空白与零宽字符**。
     *
     * <p>去空白不是为了好看，是为了堵绕过：{@code 忽 略 之 前 的 指 令} 和
     * {@code 忽\u200b略之前的指令} 在关键词匹配下都会漏网，归一化后与原文等价。
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '\uFF01' && c <= '\uFF5E') {
                c = (char) (c - 0xFEE0); // 全角 ASCII → 半角
            } else if (c == '\u3000') {
                c = ' '; // 全角空格
            }
            if (Character.isWhitespace(c)
                    || c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 剔除「番」的非动漫同形词，避免番茄/番禺之流被当成动漫信号 */
    private static String stripFalseFriends(String text) {
        String out = text;
        for (String word : FALSE_FRIENDS) {
            if (out.contains(word)) {
                out = out.replace(word, "");
            }
        }
        return out;
    }
}
