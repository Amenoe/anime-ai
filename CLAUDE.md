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
  `ChatService` 按请求构造 `MessageWindowChatMemory` 并预置历史（窗口大小 = 历史条数 + 2，不再二次裁剪），
  再据此构造 `AiServices` 代理。因此 `AiConfig` **不提供** `AnimeAssistant` bean。
  若改回 `@MemoryId` + `chatMemoryProvider` 的单例方案，会与 MySQL 形成两份记忆，
  表现为「服务重启后模型忘了上下文、但界面还显示着历史」。

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
