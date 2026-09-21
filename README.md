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
# 必需：对话模型 key（DeepSeek）
export AI_CHAT_API_KEY=sk-xxxx

# 可选：内部令牌（不设则内部接口完全不鉴权，仅限本地开发）
export AI_INTERNAL_TOKEN=your-shared-secret

mvn spring-boot:run
# 监听 8013
```

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

> ⚠️ **DeepSeek 没有 embedding 接口**，向量化必须用第二家供应商（SiliconFlow）。

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
