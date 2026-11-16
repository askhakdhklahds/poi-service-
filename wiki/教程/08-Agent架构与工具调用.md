# 08 Agent 架构与工具调用

## 目标

能讲清 EchoMind 精简版 Agent 的分层结构、一次 `ask` 的完整链路，以及为什么 LLM 不直接算空间。

## 包结构

```
agent/
├── orchestrator/   AgentOrchestrator, AgentType, AgentRequest/Response, AgentProfile(s)
├── role/           BaseAgent, GeneralAgent, SpatialQueryAgent, RoutePlanningAgent, ReportAgent
├── intent/         IntentRecognizer, IntentCategory, IntentResult
├── memory/         ConversationState, ConversationStore
├── tool/           ToolDefinition, ToolResult, ToolRegistry
├── verify/         AnswerVerifier
├── compose/        ResponseComposer
├── trace/          TraceStore, ToolCallTrace
└── monitor/        AgentMonitor
```

## 一次请求

```
POST /api/agent/ask
  → AgentController
  → AgentOrchestrator
  → ConversationStore.rewrite（多轮追问改写）
  → IntentRecognizer（关键词 + 可选 LLM）
  → AgentProfiles 选角色
  → BaseAgent 执行
  → ToolRegistry 调 GisTools
  → AnswerVerifier
  → ResponseComposer
  → ConversationStore / TraceStore / AgentMonitor
```

## 四个角色

顶层意图只保留 4 类：`QUERY` / `ROUTE` / `REPORT` / `GENERAL`。
`QUERY` 内部用 `entities.scene` 区分 NEARBY / NEAREST / BUFFER_STATS / DISTRICT_STATS / RANKING / COMPARE。

| 角色 | 职责 | 工具 |
|---|---|---|
| GENERAL | 问候、能力介绍、兜底 | search_place, list_districts |
| SPATIAL_QUERY | 周边、最近邻、缓冲区、行政区、排名、对比 | pois_within, nearest_pois, buffer_stats, district_stats, district_ranking, district_center, default_center |
| ROUTE_PLANNING | A→B 多路线（车/人）、多站点行程 | nearest_pois, route_plan, route_compare |
| REPORT | 诊断报告 | district_ranking, buffer_stats |

角色契约在 `src/main/resources/agents/profiles.json`：mission、workflow、toolScope、llmEnabled、temperature。

## 工具注册表

`agent/tool/ToolRegistry.java`：

- 白名单：每个工具声明允许的 Agent。
- 必填参数校验。
- 超时：`CompletableFuture.get(timeout)`。
- 统计：`ToolResult(success/data/error/latency)`。

## 多轮记忆

`agent/memory/ConversationStore.java`：

- 每个 session 最近 12 条消息。
- 上下文保存 `place/lon/lat/radius/category`。
- 追问改写：“那餐厅呢” → “在<上次地点>附近 餐厅”。

## 校验与观测

- `AnswerVerifier`：空答案、无工具证据、`NaN/Infinity`。
- `TraceStore`：最近 200 个 requestId 的工具轨迹。
- `AgentMonitor`：请求数、成功率、平均耗时、各角色调用数。
- 接口：`GET /api/agent/monitor`、`GET /api/agent/trace/{requestId}`。
- `agent.html` 显示 `intent / agentType / engine / toolsUsed / latencyMs`，执行轨迹默认展开，并提供 trace link。
- 空间查询秒回是确定性 SQL 路径，不是没调用 Agent。

## LLM 与确定性

- GENERAL 可走 LLM（`llmEnabled=true`）；SPATIAL_QUERY/ROUTE/REPORT 默认走确定性路径。
- LLM 只负责理解与表达；空间计算、距离、数量全部来自 `GisTools`。
- LLM 失败时回退规则/工具，服务仍可用。

## 面试问答

**Q：为什么不让 LLM 直接生成 SQL？**

A：安全（SQL 注入/越权）、正确性（空间计算易错）、可解释（steps 可追踪）。

**Q：为什么复杂意图走确定性 Agent？**

A：比较/路线/排名对计算正确性要求高，代码可复现、可解释。

**Q：多轮记忆存哪？**

A：当前 `ConversationStore` 内存，重启丢失；生产换 Redis + TTL。

**Q：怎么防幻觉？**

A：空间数据和数字由工具返回；AnswerVerifier 检查无工具证据；提示词明确不要编造坐标/数量。
