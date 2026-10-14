# anime-ai

番剧 AI 服务（Java / Spring Boot）。为 `anime-chat` 提供对话助手与检索能力，
是位于 `anime-chat`（Vue 前端）与 `anime-chat-server`（NestJS 业务后端）之后的第三个服务。

跨仓文档在工作区根目录 `docs/`：

- `docs/ai-rag-practice-roadmap.md` — 能力建设路线与里程碑（M1…M6）
- `docs/ai-chat-tech-stack.md` — 对话工具技术栈与 SSE 协议
- `docs/PROJECT_MEMORY.md` — 任务看板与修复记录

## 当前进度：M1（链路打通，不含 RAG）

已实现：

- `POST /internal/ai/chat` — SSE 流式对话（DeepSeek）
- 工具调用：`browse_anime`（榜单浏览）+ `search_anime`（标题检索）
- 服务间共享令牌鉴权
- **无状态**：会话历史由上游 MySQL 提供，本服务不保留上下文
- Bangumi 客户端 + 真实接口测试

未实现（M2 起）：向量检索、Qdrant、embedding、评估脚本。

## 请求契约

```jsonc
// 新格式（推荐）：完整消息窗口，最后一条必须是本轮用户消息
{ "conversationId": "c1",
  "messages": [ {"role":"user","content":"我想看科幻"},
                {"role":"assistant","content":"好的"},
                {"role":"user","content":"推荐几部"} ] }

// 旧格式（便捷/调试用）：单轮，等价于只有一条 user 消息
{ "message": "推荐几部科幻番" }
```

两者二选一；都为空返回 400。

> **为什么是无状态的**：会话历史只有一个权威来源（`anime-chat-server` 的 MySQL），
> 否则会出现「本服务重启后忘了、但界面还显示着历史」的不一致，且多实例部署需要粘性会话。
> 窗口大小由上游决定，本服务不再二次裁剪。

## 运行

```bash
# 配置走项目根目录的 .env（会自动加载，不必 export）
cp .env.example .env
#   至少填 AI_CHAT_API_KEY（DeepSeek）
#   AI_INTERNAL_TOKEN 要与 anime-chat-server 的一致

mvn spring-boot:run
# 监听 8013
```

`.env` 已被 `.gitignore` 排除；模板见 `.env.example`。

自检：

```bash
curl -X POST http://127.0.0.1:8013/internal/ai/ping -H "X-Internal-Token: $AI_INTERNAL_TOKEN"
# {"service":"anime-ai","model":"deepseek-flash","status":"ok"}

curl -N -X POST http://127.0.0.1:8013/internal/ai/chat \
  -H 'Content-Type: application/json' -H "X-Internal-Token: $AI_INTERNAL_TOKEN" \
  -d '{"message":"推荐几部科幻番"}'
```

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `AI_CHAT_API_KEY` | 空 | **必填**，DeepSeek key |
| `AI_CHAT_BASE_URL` | `https://api.deepseek.com/v1` | OpenAI 兼容端点 |
| `AI_CHAT_MODEL` | `deepseek-flash` | 另有 `deepseek-v4-pro`（贵约 4 倍） |
| `AI_INTERNAL_TOKEN` | 空 | 服务间共享密钥，空 = 关闭校验 |
| `AI_EMBEDDING_API_KEY` | 空 | SiliconFlow key，配了才会创建 embedding bean（M2 才用） |
| `AI_EMBEDDING_MODEL` | `BAAI/bge-m3` | 1024 维；**需在控制台确认确切 model id** |
| `AI_BANGUMI_UA` | `anime-ai/0.1 …` | Bangumi 要求带 User-Agent |
| `AI_QDRANT_URL` | `http://127.0.0.1:6333` | M2 Qdrant REST 地址 |
| `AI_QDRANT_COLLECTION` | `anime_knowledge` | M2 向量集合名 |
| `AI_CORPUS_JDBC_URL` | 空 | 可选，只读本地 `anime` 表；空时仍可从 Bangumi 导入 |
| `AI_CORPUS_DB_USERNAME` / `AI_CORPUS_DB_PASSWORD` | 空 | 本地语料库连接凭据 |

> ⚠️ **DeepSeek 没有 embedding 接口**，向量化必须用第二家供应商（SiliconFlow）。

## M2：知识库索引与检索

### Qdrant 在这里做什么

Qdrant 是向量数据库。它不负责调用 LLM，也不负责生成答案，只保存和搜索两类数据：

- **vector**：BGE-M3 为一部番剧简介生成的 1024 维向量，用 Cosine 相似度搜索；
- **payload**：`subject_id`、标题、标签、年份、评分、简介和 `content_hash`，用于前端回链、过滤和增量更新。

当前集合名是 `anime_knowledge`，一部番剧对应一个 point，point id 就是 Bangumi `subject_id`。`KnowledgeService` 负责 embedding 和增量逻辑，`QdrantClient` 只负责调用 Qdrant REST API。

### 启动、查看和停止

配置 `AI_EMBEDDING_API_KEY`（SiliconFlow）后启动 Qdrant：

```bash
docker compose -f docker-compose.rag.yml up -d
```

查看容器和集合：

```bash
docker compose -f docker-compose.rag.yml ps
curl http://127.0.0.1:6333/collections
curl http://127.0.0.1:6333/collections/anime_knowledge
```

停止容器但保留向量数据：

```bash
docker compose -f docker-compose.rag.yml stop
```

删除容器并清空向量数据：

```bash
docker compose -f docker-compose.rag.yml down -v
```

`down -v` 会删除 `qdrant_storage` volume，下一次必须重新导入语料；普通 `stop` 或 `down` 不会删除数据。

未配置本地语料库时，直接从 Bangumi 导入 3000 条动画简介、标签及元数据（分页限速 1 req/s；重复导入只重算变化项）：

```bash
curl -X POST http://127.0.0.1:8013/internal/knowledge/ingest \
  -H 'Content-Type: application/json' -H "X-Internal-Token: $AI_INTERNAL_TOKEN" \
  -d '{"source":"bangumi","limit":3000}'
```

若已配置本地 `anime` 表连接，省略 `source` 会从 MySQL 读取最多 3000 条；也可显式传 `source:"mysql"`。显式传 `source:"bangumi"` 总是从 Bangumi 导入。
只读检索接口不调用聊天模型，供 M3 离线评估直接使用：

```bash
curl -X POST http://127.0.0.1:8013/internal/retrieve \
  -H 'Content-Type: application/json' -H "X-Internal-Token: $AI_INTERNAL_TOKEN" \
  -d '{"query":"时间循环 悬疑","topK":5}'
```

过滤器支持 `yearFrom`、`minRating`、`metaTags`，在 Qdrant 召回前执行。M2 每部番是一条向量，不对短简介硬切 chunk；`/internal/ai/chat` 已将模糊回忆请求路由到 `search_knowledge`。索引入口受同一内部令牌保护，应只在可信内网/本地调用。

## SSE 事件协议

前端是手写 `fetch` + `ReadableStream` 解析的，这是两端的契约（详见 `docs/ai-chat-tech-stack.md` §4.5）：

```
event:text-delta    data:{"text":"..."}
event:tool-call     data:{"name":"browse_anime","args":{...}}
event:tool-result   data:{"name":"browse_anime","failed":false,"subjects":[{"id":…,"nameCn":…}]}
event:usage         data:{"promptTokens":…,"completionTokens":…}
event:error         data:{"message":"..."}
event:done          data:{"conversationId":"...","finishReason":"STOP"}
```

注意：`data:` 后**没有空格**，前端解析要兼容；`data` 恒为单行 JSON。

## Bangumi 接口的实测行为（重要）

这两个接口能力差异很大，工具设计建立在此之上：

| 接口 | 用途 | 实测表现 |
| --- | --- | --- |
| `GET /v0/subjects?type=2&sort=rank[&year][&month]` | **推荐番剧（主力）** | 质量高。总榜返回攻壳机动队(9.2)、星际牛仔(9.1)、命运石之门(8.8)；`year=2023` 返回葬送的芙莉莲(8.5) |
| `POST /v0/search/subjects` | **标题查找** | 标题匹配精准（`命运石之门` → 50 条全相关） |

⚠️ **`keyword` 是标题字面匹配，不是语义/标签检索。** 把题材当关键词会返回噪音：
`keyword=科幻` 只命中 4 部标题里含「科幻」的条目；`keyword=动画 + tag=[科幻]` 的 top 结果里
会出现「憨豆先生动画版」。所以**题材发现必须走 `browse_anime`，不能走 `search_anime`**。

这条实测结论也修正了早期文档里「Bangumi 结构化过滤足以覆盖找番需求」的假设 —— 详见
`docs/ai-rag-practice-roadmap.md`。

## 已知坑（都已处理，改动时别踩回去）

1. **Boot 4 用 Jackson 3，LangChain4j 用 Jackson 2**：classpath 上并存。
   Boot 4 **不再**自动配置 `com.fasterxml.jackson.databind.ObjectMapper` bean（注入会导致启动失败）；
   且 DTO 解析交给谁是不确定的。因此响应解析与 SSE 序列化统一走 `support/Json.MAPPER`（Jackson 2）。
2. **`ObjectMapper` 别注入**：见上。
3. **每个内部接口都要调 `assertInternalToken`**：曾经漏了 `/ping`，导致配了令牌后探测接口仍匿名可用。
4. **SSE 的 `data` 必须单行 JSON**：`SseWriter` 会把换行替换掉。
5. **客户端断开是常态**：`SseWriter` 吞掉写出异常，避免刷日志。
6. **Bangumi `rating.count` 是对象**（分数分布），不是整数 —— 实测才发现。
7. **Bangumi `name_cn` 可能是空字符串**：`toCard` 兜底到 `name`，否则前端渲染空白标题。

## 测试

```bash
mvn test
```

`BangumiClientTest` **打真实 Bangumi 接口**（需联网）。这不是偷懒：字段映射错误
（如 `name_cn` 静默变 null、`rating.count` 类型不符）打桩测不出来，只能靠真实响应。

## 技术栈

Spring Boot 4.0.8 · Java 21 · LangChain4j 1.20.0（BOM 同时管住 GA 与 beta 两套版本）· Maven
