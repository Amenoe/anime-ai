# CLAUDE.md

本文件指导在 `anime-ai` 仓库内的工作。跨仓约定见工作区根目录 `docs/`。

## 项目定位

`anime-ai` 是番剧 AI 服务（Java / Spring Boot），只负责**检索与模型编排**，
不碰业务数据（用户、房间、播放）——那些属于 `anime-chat-server`（NestJS）。

调用链：`anime-chat`(Vue) → `anime-chat-server`(NestJS，鉴权/配额/转发) → `anime-ai`(本仓) → DeepSeek / Bangumi

**架构边界要守住**：NestJS 不写检索逻辑，本仓不碰业务库（M2 只读 `anime` 表）。

## 命令

```bash
mvn spring-boot:run          # 启动，监听 8013
mvn -q -B compile            # 编译
mvn test                     # 测试（BangumiClientTest 打真实 Bangumi 接口，需联网）
mvn -B package -DskipTests   # 打包
```

Maven 不在默认 PATH 时：`export PATH="$PATH:/Users/rain/Java/apache-maven-3.9.9/bin"`。

## 配置

配置来自项目根目录的 `.env`（由 `application.yml` 的 `spring.config.import` 加载，
**不需要 export 到 shell**）：

```bash
cp .env.example .env   # 然后填 AI_CHAT_API_KEY
```

- `.env` 已被 `.gitignore` 排除，**绝不提交**；`.env.example` 是模板，需要更新字段时改它
- `AI_INTERNAL_TOKEN` 必须与 `anime-chat-server` 的**完全一致**，否则转发被拒 401
- `.env` 必须在**进程工作目录**下；在别处跑 jar 要改成绝对路径 `file:/path/to/.env[.properties]`

## 语言与风格

- 注释、日志、commit、UI 文案一律中文
- Commit：emoji 前缀 Angular 风格（`🌟feat(scope): subject`），与另两个仓一致
- 时间/费用敏感逻辑要写清「为什么」，不要只写「做了什么」

## 关键架构决策（改动前先读）

1. **只用手工装配，不用 langchain4j-spring-boot4-starter**
  该 starter 在 beta 线（`1.20.0-beta30`），属性名与自动装配行为可能在小版本间变动。
  `AiConfig` 显式声明 bean，便于排查。

2. **本服务是无状态的 —— 不要把会话记忆做成单例 bean**
  历史由 `anime-chat-server` 的 MySQL 持有，每次请求随 `messages` 传入。
  `ChatService` 按请求构造 `MessageWindowChatMemory` 并预置历史，再据此构造 `AiServices` 代理。
  因此 `AiConfig` **不提供** `AnimeAssistant` bean。
  若改回 `@MemoryId` + `chatMemoryProvider` 的单例方案，会与 MySQL 形成两份记忆，
  表现为「服务重启后模型忘了上下文、但界面还显示着历史」。

  ⚠️ **记忆窗口必须按工具轮数预留槽位（这是踩过的坑）**：
  一次工具往返占 **3** 个槽 —— `user`、`ai(toolCall)`、`toolResult`。
  曾写成 `history.size() + 2`，单轮请求时窗口只有 2，toolResult 一进来就把 **user 挤出去**，
  第二次请求的上下文里只剩「工具调用 + 结果」，模型于是**答非所问地重新打招呼**。
  现在按 `history.size() + 1 + 2 * MAX_TOOL_ROUNDS` 预留，并用
  `maxToolCallingRoundTrips(MAX_TOOL_ROUNDS)` 兜住失控循环 —— **两者是一对，改一个要改另一个**。
  这个 bug 只有用**真实 key** 跑完整链路才暴露得出来（假 key 只回 error 事件）。

3. **Jackson 2 / Jackson 3 并存 —— 这是本仓最容易踩的坑**
  - Boot 4 默认 **Jackson 3**（`tools.jackson.core:jackson-databind:3.x`）
  - LangChain4j 1.20.0 仍基于 **Jackson 2**（`com.fasterxml.jackson.core:2.21.x`）
  - 后果一：Boot 4 **不再**自动配置 `com.fasterxml.jackson.databind.ObjectMapper` bean，
    注入它会让应用**启动失败**。
  - 后果二：谁解释 `@JsonProperty` 变成隐式行为，出错时字段**静默变 null**。
  - 对策：所有自有序列化/反序列化走 `com.animeai.support.Json.MAPPER`（Jackson 2，与 LangChain4j 同源）。
    Spring MVC 自己的请求/响应转换仍交给 Boot 4 的 Jackson 3 —— 我们的 REST DTO 不带 Jackson 注解。

4. **工具设计依据实测，不是推测**
  `POST /v0/search/subjects` 的 `keyword` 是**标题字面匹配**，没有语义/标签召回。
  用题材当 keyword 会返回噪音（实测 `keyword=动画 + tag=[科幻]` 的首条是「憨豆先生动画版」）。
  因此「推荐番剧」走 `browse_anime`（`GET /v0/subjects?sort=rank`），
  `search_anime` 只用于**用户说出作品名**的场景。
  改工具描述或系统提示前，先跑 `BangumiClientTest` 确认接口实际行为。

5. **服务间鉴权**：`assertInternalToken` 必须被**每个**内部入口调用。
  曾经只给 `/chat` 加校验、漏了 `/ping`，配了令牌后探测接口依然匿名可用。

6. **SSE 协议是前后端契约**，改事件名/字段要同步改：
  `docs/ai-chat-tech-stack.md` §4.5、本仓 `SseEvent`、前端解析器。
  新增事件时保持前端「忽略未知事件」的容错，才能向后兼容。

7. **话题范围守卫（`TopicScopeGuard`）：拒答是 fail-open 的，别把它改成「猜得更准」**
  - `ChatService.stream()` 在**发起模型调用之前**调 `TopicScopeGuard.judge(本轮输入)`；
    命中就直接回绝，全程 0 token。
  - 判定是**非对称**的：拒答需要正面证据（命中越界词**且**无动漫信号），放行不需要。
    误伤正常动漫提问（用户直接走掉）远比漏放一次跑题（浪费一次生成）严重 ——
    这是刻意的，不是没调好。**收紧规则前先想清楚会不会误伤。**
  - 守卫**只看本轮输入，不看 history**。把历史拉进来会让规则不可判定
    （任何跑题请求都能靠「上一轮聊过番」混过去），而且单测必须构造历史才能覆盖。
    「还有吗」「继续」这类省略语境的追问靠 fail-open 放行，不靠读历史。
  - 守卫**刻意不做按长度豁免**：短句也可能是明确越界的（「今天天气怎么样」只有 7 个字）。
    短句的豁免体现在「不需要命中白名单」，而不是「不受黑名单约束」。
  - 它是**纯静态类、无 Spring 依赖**，单测 `TopicScopeGuardTest` 不联网不启容器。
    改词表后**先跑它**（它绿了不代表规则对，但红了通常就是误伤）。
  - 守卫与系统提示词是**分层**的：守卫保守（漏的交给模型），提示词兜剩余。
    「翻译一下命运石之门」就属于守卫放行、提示词礼貌拒答的那种 —— 属于设计预期。

## Bangumi 接口注意事项

- 必须带 `User-Agent`（已配默认值）
- `POST /v0/search/subjects` 的 `keyword` **必填非空**，空串直接 400（`resolveKeyword` 已兜底）
- `rating.count` 是**分数分布对象**，不是整数
- `name_cn` 可能是**空字符串**（字段存在但为空），需兜底到 `name`
- 有速率限制，语料同步任务（M2）要串行 + 限速
- 本机 curl 连不上 `api.bgm.tv`（connect=0），但 Java 客户端正常 —— 调试时别只信 curl

## M2 预留

- `langchain4j-qdrant` 由 BOM 自动对齐版本，加依赖即可
- Embedding：SiliconFlow BGE-M3（1024 维），`EmbeddingModel` bean 已按 api-key 条件装配
- 检索接口设计（**必须**与生成拆开，否则离线评估无法进行）见 `docs/ai-rag-practice-roadmap.md` §3.6
