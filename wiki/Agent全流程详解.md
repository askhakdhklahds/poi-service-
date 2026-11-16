# Agent 全流程详解

> 这篇只讲 Agent：从 `POST /api/agent/ask` 进来，到 `AgentResponse` 返回，每一步做了什么、调了哪个类、用了哪段代码、返回什么。
> 主流程文档见 [项目全流程详解](项目全流程详解.md)。

---

## 1. 先看全局

### 1.1 4 个顶层意图

| 顶层意图 | 谁处理 | 子场景 |
|---|---|---|
| `QUERY` | `SpatialQueryAgent` | 附近 / 最近 / 缓冲区统计 / 行政区统计 / 排名 / 对比 |
| `ROUTE` | `RoutePlanningAgent` | A→B 多路线、车/人、沿途特点 |
| `REPORT` | `ReportAgent` | 空间诊断报告 |
| `GENERAL` | `GeneralAgent` | 问候、能力介绍、兜底 |

为什么只保留 4 类：

- 顶层意图只负责**路由到角色**；
- 查询内部用 `entities.scene` 区分具体场景；
- 避免“9 类意图 + 9 个 if/else”的爆炸。

### 1.2 4 个角色

| 角色 | 类 | 职责 | 工具 |
|---|---|---|---|
| GENERAL | `GeneralAgent` | 问候、兜底 | search_place, list_districts |
| SPATIAL_QUERY | `SpatialQueryAgent` | 周边/最近/统计/排名/对比 | pois_within, nearest_pois, buffer_stats, district_stats, district_ranking, district_center, default_center |
| ROUTE_PLANNING | `RoutePlanningAgent` | A→B 多路线、多站点行程 | nearest_pois, route_plan, route_compare |
| REPORT | `ReportAgent` | 诊断报告 | district_ranking, buffer_stats |

### 1.3 包结构

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

---

## 2. 一次请求的 11 步

入口：

```java
// AgentController
@PostMapping("/ask")
public Map<String, Object> ask(@RequestBody AskRequest request) {
    String question = request.question() == null ? "" : request.question().trim();
    if (question.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "question required");
    String sessionId = sessionId(request.sessionId());
    AgentResponse data = orchestrator.run(question, sessionId);
    return Map.of("code", 0, "message", "ok", "data", data);
}
```

`AgentOrchestrator.run` 是总编排：

```java
public AgentResponse run(String question, String sessionId) {
    String sid = sessionId == null || sessionId.isBlank()
            ? UUID.randomUUID().toString() : sessionId;

    // ① 会话 ID
    // ② 多轮追问改写
    String rewritten = conversationStore.rewrite(question, sid);

    // ③ 意图识别 + 实体抽取
    IntentResult intent = intentRecognizer.recognize(rewritten);

    // ④ 意图 → 角色
    AgentType type = route(intent);

    // ⑤ 取角色契约
    AgentProfile profile = agentProfiles.get(type);

    // ⑥ 组装输入包
    AgentRequest request = new AgentRequest(
            UUID.randomUUID().toString(), rewritten, "anonymous", sid,
            intent, conversationStore.history(sid), conversationStore.context(sid));

    // ⑦ 执行角色
    AgentResponse response = agents.get(type).run(request, profile, intent);

    // ⑧ 校验
    response = answerVerifier.verify(response, intent);

    // ⑨ 汇总
    response = responseComposer.compose(response);

    // ⑩ 写记忆 + 更新上下文
    conversationStore.add(sid, "user", question);
    conversationStore.add(sid, "assistant", response.getAnswer());
    updateContext(sid, question, response);

    // ⑪ 轨迹 + 监控
    traceStore.record(response.getRequestId(), response.getSteps());
    agentMonitor.record(response);
    return response;
}
```

每一步的输入/输出：

| 步 | 做什么 | 输入 | 输出 |
|---|---|---|---|
| ① | 生成/复用 sessionId | 请求里的 sessionId | 最终的 sid |
| ② | 追问改写 | 原问题 + 上下文 | 改写后的问题 |
| ③ | 意图识别 | 改写后的问题 | `IntentResult(category, confidence, entities)` |
| ④ | 路由 | `IntentResult` | `AgentType` |
| ⑤ | 取角色契约 | `AgentType` | `AgentProfile` |
| ⑥ | 组装请求 | 以上全部 | `AgentRequest` |
| ⑦ | 执行角色 | `AgentRequest` + `AgentProfile` | `AgentResponse` |
| ⑧ | 校验 | `AgentResponse` + `IntentResult` | 修正后的 `AgentResponse` |
| ⑨ | 汇总 | `AgentResponse` | 最终回答 |
| ⑩ | 写记忆 | 问题 + 回答 | 更新后的会话 |
| ⑪ | 观测 | `requestId` + `steps` | Trace + 指标 |

---

## 3. 意图识别详解

### 3.1 入口

```java
// IntentRecognizer.recognize
public IntentResult recognize(String question) {
    IntentResult ruleResult = recognizeByKeywords(question);
    if (!llmEnabled || !llmClient.isConfigured()) return ruleResult;
    try {
        String answer = llmClient.complete(prompt, question);
        IntentCategory category = parseCategory(answer);
        if (category != null) {
            return new IntentResult(category, Math.max(0.8, ruleResult.confidence()), ruleResult.entities());
        }
    } catch (Exception e) {
        log.warn("LLM intent recognition failed, fallback to keywords: {}", e.getMessage());
    }
    return ruleResult;
}
```

### 3.2 关键词分类

`recognizeByKeywords` 先算几个布尔量：

```java
boolean nearby = containsAny(q, "附近", "周围", "周边", "离我");
boolean stats = containsAny(q, "统计", "分布", "多少", "数量", "各类", "几个", "几家");
boolean district = q.contains("行政区") || q.contains("哪个区")
        || q.matches(".*[\\u4e00-\\u9fa5]{2,10}区.*");
boolean routeWords = containsAny(q, "路线", "怎么走", "导航", "行程", "顺路")
        || fromTo != null;
boolean report = containsAny(q, "报告", "诊断", "评估报告");
```

优先级：

```
REPORT → ROUTE → QUERY → GENERAL
```

`QUERY` 内部再判 `scene`：

```
比较/对比 → COMPARE
排名/排行/哪个区/最多/哪类 → RANKING
附近 + 统计 → BUFFER_STATS
某区 + 统计 → DISTRICT_STATS
最近 → NEAREST
附近 → NEARBY
其他 → NEARBY
```

### 3.3 实体抽取

| 实体 | 正则/方法 | 例子 |
|---|---|---|
| `category` | `CATEGORY_KEYWORDS` | 医院、餐饮、购物、学校、交通、景点、商业 |
| `radius` | `(\d+)\s*(公里|千米|km|米|m)` | 1公里 → 1000 |
| `limit` | `(?:最近|前|top)\s*(\d+)\s*个` | 最近10个 → 10 |
| `place` | `从(.+?)出发`、`离(.+?)最近`、`在(.+?)附近`、`(.+?区)` | 外滩、黄浦区 |
| `from/to` | `从(.+?)到(.+?)` | 外滩 → 陆家嘴 |

### 3.4 例子

| 问题 | 意图 | scene | entities |
|---|---|---|---|
| 附近1公里有哪些医院 | QUERY | NEARBY | category=医院, radius=1000 |
| 黄浦区各类POI有多少 | QUERY | DISTRICT_STATS | place=黄浦区 |
| 外滩附近1公里餐饮统计 | QUERY | BUFFER_STATS | place=外滩, category=餐饮, radius=1000 |
| 哪个区POI最多 | QUERY | RANKING | - |
| 从外滩到陆家嘴怎么走 | ROUTE | - | from=外滩, to=陆家嘴 |
| 生成空间诊断报告 | REPORT | - | - |
| 你好 | GENERAL | - | - |

### 3.5 为什么关键词优先

- 确定性高、零成本、可解释；
- 空间问句里的“附近/最近/排名/从A到B”很稳定；
- LLM 只在配置开启时做增强，失败自动回退。

---

## 4. 路由与角色契约

### 4.1 路由

```java
private AgentType route(IntentResult intent) {
    return switch (intent.category()) {
        case QUERY -> AgentType.SPATIAL_QUERY;
        case ROUTE -> AgentType.ROUTE_PLANNING;
        case REPORT -> AgentType.REPORT;
        case GENERAL -> AgentType.GENERAL;
    };
}
```

### 4.2 角色契约

`AgentProfiles` 启动时读取 `resources/agents/profiles.json`：

```java
List<AgentProfile> list = mapper.readValue(in, new TypeReference<List<AgentProfile>>() {});
Map<AgentType, AgentProfile> map = new EnumMap<>(AgentType.class);
for (AgentProfile profile : list) map.put(profile.agentType(), profile);
for (AgentType type : AgentType.values()) {
    if (!map.containsKey(type)) throw new IllegalStateException("missing agent profile: " + type);
}
```

`profiles.json` 的一个角色：

```json
{
  "agentType": "SPATIAL_QUERY",
  "role": "空间查询 Agent",
  "mission": "回答附近有什么、最近的是谁、某类设施数量、行政区统计等问题。",
  "workflow": ["解析地点/分类/半径", "解析地点坐标", "调用工具", "汇总结果"],
  "inputContract": ["地点", "分类", "半径", "数量上限"],
  "outputContract": ["数量", "名称", "距离", "分类统计"],
  "toolScope": ["search_place", "pois_within", "nearest_pois", "..."],
  "llmEnabled": false,
  "temperature": 0.1,
  "maxOutputTokens": 900
}
```

`AgentProfile.contract()` 把契约渲染成一段文本，可拼进 system prompt：

```java
sb.append("角色：").append(role).append('\n');
sb.append("职责：").append(mission).append('\n');
appendList(sb, "处理流程", workflow);
appendList(sb, "输入约束", inputContract);
appendList(sb, "输出约束", outputContract);
sb.append("可用工具：").append(String.join(", ", toolScope)).append('\n');
sb.append("不要编造坐标、数量或设施名称；所有空间数据必须来自工具返回。\n");
```

**注意**：`toolScope` 是“契约/文档”，真正执行时的权限在 `ToolRegistry` 的 `allowedAgents`。两者当前人工保持一致。

---

## 5. BaseAgent：LLM 与确定性两条路径

```java
public AgentResponse run(AgentRequest request, AgentProfile profile, IntentResult intent) {
    long start = System.nanoTime();
    AgentResponse base;

    if (profile.llmEnabled() && llmClient.isConfigured()) {
        try {
            base = AgentResponse.fromLegacy(
                    llmClient.ask(request.message(), request.history()));
        } catch (Exception e) {
            log.warn("{} LLM path failed, fallback to deterministic: {}", type(), e.getMessage());
            base = AgentResponse.fromLegacy(executeDeterministic(request, intent));
        }
    } else {
        base = AgentResponse.fromLegacy(executeDeterministic(request, intent));
    }

    if (base.getAnswer() == null || base.getAnswer().isBlank()) {
        base = AgentResponse.builder()
                .answer("没有找到符合条件的 POI，请换个地点或分类再试。")
                .success(false)
                .build();
    }
    return decorate(base, request, intent, start);
}
```

两条路径：

| 路径 | 条件 | 走什么 |
|---|---|---|
| LLM | `profile.llmEnabled=true` 且 Key 可用 | `LlmAgentClient.ask` |
| 确定性 | 其他 | 子类 `executeDeterministic` |

`decorate` 最后补上：

- `requestId`、`sessionId`；
- `intent`（顶层意图）；
- `agentType`；
- `toolsUsed`（从 steps 里提取）；
- `latencyMs`；
- `engine`。

---

## 6. SpatialQueryAgent 详解

### 6.1 入口

```java
protected Map<String, Object> executeDeterministic(AgentRequest request,
                                                   IntentResult intent) {
    Map<String, Object> entities = intent.entities();
    String place = str(entities.get("place"));
    String category = str(entities.get("category"));
    int radius = integer(entities.get("radius"), 1000);
    int limit = integer(entities.get("limit"), 10);

    String scene = str(entities.get("scene"));
    if (scene == null) scene = "NEARBY";
    if ("DISTRICT_STATS".equals(scene) && place == null) scene = "RANKING";
    ...
}
```

### 6.2 地名解析

```java
boolean preferDistrict = "DISTRICT_STATS".equals(scene)
        || ("RANKING".equals(scene) && place != null
        && !containsAny(request.message(), "附近", "周围", "周边"));
Place resolved = preferDistrict && place != null
        ? resolveDistrict(place, steps)
        : resolvePlace(place, steps);
```

`resolvePlace`：

1. 如果地名以“区/县”结尾，先走 `district_center`；
2. 否则先走 `search_place`；
3. 找不到再走 `district_center`；
4. 还找不到就用 `default_center`。

```java
private boolean looksLikeDistrict(String place) {
    return place.endsWith("区") || place.endsWith("县");
}
```

这是实际踩过的 bug：`search_place("黄浦区")` 会先匹配到 POI“黄浦区工人体育馆”，导致 `district_stats` 查错；改成行政区名优先走行政区中心。

### 6.3 按 scene 调工具

```java
switch (scene) {
    case "NEAREST" ->      tools.execute("nearest_pois", type(), args);
    case "BUFFER_STATS" -> tools.execute("buffer_stats", type(), args);
    case "DISTRICT_STATS" -> tools.execute("district_stats", type(), args("nameOrCode", nameOrCode));
    case "RANKING" ->      nearby ? tools.execute("buffer_stats", ...)
                                  : tools.execute("district_ranking", ...);
    case "COMPARE" ->      advancedAgent.tryAsk(request.message()); // 复用旧对比逻辑
    default ->             tools.execute("pois_within", type(), args);
}
```

### 6.4 生成回答和 steps

每个工具调用都会写进 `steps`：

```java
private void addStep(List<Map<String, Object>> steps, String tool,
                     String input, ToolResult result) {
    Map<String, Object> step = new LinkedHashMap<>();
    step.put("tool", tool);
    step.put("input", input);
    step.put("output", result.success() ? summarize(result.data()) : result.error());
    step.put("ms", result.latencyMs());
    steps.add(step);
}
```

返回的 Map 包含：

```java
out.put("intent", scene.toLowerCase(Locale.ROOT));
out.put("answer", answer);
out.put("data", data);
out.put("center", center(resolved.lon, resolved.lat));
out.put("radius", radius);
out.put("steps", steps);
out.put("cards", cards);
out.put("place", resolved.name);
out.put("placeFound", resolved.found);
out.put("engine", "tool-registry");
```

---

## 7. RoutePlanningAgent 详解

### 7.1 入口

```java
protected Map<String, Object> executeDeterministic(AgentRequest request,
                                                   IntentResult intent) {
    String fromName = str(intent.entities().get("from"));
    String toName = str(intent.entities().get("to"));

    if (fromName != null && toName != null) {
        RoutePoint from = resolvePoint(fromName);
        RoutePoint to = resolvePoint(toName);
        if (from != null && to != null) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("fromLon", from.lon());
            args.put("fromLat", from.lat());
            args.put("fromName", from.name());
            args.put("toLon", to.lon());
            args.put("toLat", to.lat());
            args.put("toName", to.name());
            args.put("modes", List.of("car", "walk"));
            args.put("alternatives", 3);

            ToolResult result = tools.execute("route_compare", type(), args);
            if (result.success()) return buildRouteResult(result, from, to);
        }
    }

    // 没有 from/to：走原来的“多站点行程”逻辑
    Map<String, Object> result = advancedAgent.tryAsk(request.message());
    return result != null ? result : super.executeDeterministic(request, intent);
}
```

### 7.2 地点解析

`resolvePoint` 先 `search_place`，再 `district_center`，返回 `RoutePoint(lon, lat, name)`。

### 7.3 组装路线回答

`buildRouteResult`：

- 从 `routes` 里数出 `carCount` / `walkCount`；
- 找最快驾车、最快步行；
- 生成 answer；
- 生成 `cards`（路线对比）；
- 生成 `routePlanUrl`；
- `steps` 记录 `route_compare` 调用；
- `engine=route-plan`。

`routePlanUrl` 形如：

```
/route.html?fromLon=121.4855611&fromLat=31.2393233&fromName=外滩
&toLon=121.4981186&toLat=31.2401354&toName=陆家嘴
&modes=car,walk&alternatives=3
```

`agent.html` 收到 `routePlanUrl` 后显示“查看多路线对比”。

---

## 8. GeneralAgent / ReportAgent

- `GeneralAgent`：不重写 `executeDeterministic`，默认走 `RuleBasedAgent.ask`；`profiles.json` 里 `llmEnabled=true`，所以 Key 可用时优先走 LLM，失败回退规则。
- `ReportAgent`：`executeDeterministic` 调 `ReportService.report(sessionId)`，读取上下文、行政区排名、周边分类，生成 Markdown；LLM 不可用时模板兜底。

---

## 9. ToolRegistry 详解

### 9.1 注册

```java
register("search_place", "按名称搜索地点", Set.of("keyword"), allAgents(), 5000,
        args -> gisTools.searchPlace(str(args, "keyword")));

register("pois_within", "查询坐标附近 POI", Set.of("lon","lat","radius"),
        Set.of(AgentType.SPATIAL_QUERY), 5000,
        args -> gisTools.poisWithin(...));

register("route_compare", "规划起点到终点的多条驾车/步行路线",
        Set.of("fromLon","fromLat","toLon","toLat"),
        Set.of(AgentType.ROUTE_PLANNING), 30000,
        this::routeCompare);
```

### 9.2 执行

```java
public ToolResult execute(String name, AgentType agentType, Map<String, Object> args) {
    ToolDefinition definition = definitions.get(name);
    if (definition == null) return ToolResult.fail("unknown tool: " + name, 0);
    if (agentType != null && !definition.allowedAgents().contains(agentType))
        return ToolResult.fail("tool not allowed for agent: " + name, 0);
    for (String required : definition.requiredArgs()) {
        if (args == null || args.get(required) == null)
            return ToolResult.fail("missing arg: " + required, 0);
    }
    long start = System.nanoTime();
    try {
        Object data = CompletableFuture
                .supplyAsync(() -> handlers.get(name).apply(args), executor)
                .get(definition.timeoutMs(), TimeUnit.MILLISECONDS);
        return ToolResult.ok(data, elapsedMs(start));
    } catch (TimeoutException e) {
        return ToolResult.fail("tool timeout: " + name, elapsedMs(start));
    } catch (Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return ToolResult.fail(cause.getMessage(), elapsedMs(start));
    }
}
```

四道门：

1. 工具是否存在；
2. 当前角色是否有权限；
3. 必填参数是否齐全；
4. 是否超时。

---

## 10. 多轮记忆详解

### 10.1 存储

`ConversationStore`：

```java
private final Map<String, ConversationState> states = new ConcurrentHashMap<>();
```

`ConversationState`：

- 最近 12 条消息；
- 上下文 `place / lon / lat / radius / category`。

### 10.2 追问改写

```java
public String rewrite(String question, String sessionId) {
    Map<String, Object> context = context(sessionId);
    Object place = context.get("place");
    Object category = context.get("category");
    if (place == null) return question;

    String lastPlace = String.valueOf(place);
    if (question.contains(lastPlace)) return question;

    boolean followUp = question.startsWith("那") || question.startsWith("这")
            || question.contains("呢") || question.contains("还有")
            || question.contains("再") || question.contains("换成")
            || question.startsWith("附近") || question.startsWith("最近");
    if (!followUp) return question;

    StringBuilder rewritten = new StringBuilder()
            .append("在").append(lastPlace).append("附近 ").append(question);
    if (category != null && !containsCategory(question)) {
        rewritten.append(" ").append(category);
    }
    return rewritten.toString();
}
```

### 10.3 例子

```
第一轮：黄浦区各类POI有多少
上下文：place=黄浦区, category=null, radius=1000

第二轮：那餐厅呢
不是以“那”开头？是。
不包含“黄浦区”？是。
→ 改写成：在黄浦区附近 那餐厅呢
```

`IntentRecognizer` 再从中抽到 `place=黄浦区`、`category=餐饮`。

---

## 11. 校验、汇总、轨迹、监控

### AnswerVerifier

```java
if (answer == null || answer.isBlank()) { ... success=false; }
if (answer.contains("NaN") || answer.contains("Infinity")) { ... success=false; }
if (DATA_INTENTS.contains(intent.category()) && response.getSteps().isEmpty()) {
    answer = "没有获取到空间数据，请换个地点或分类再试。";
    success = false;
}
```

`DATA_INTENTS = {QUERY, ROUTE}`。

### ResponseComposer

当前透传，保留多 Agent 合并的扩展点。

### TraceStore

- `requestId → List<ToolCallTrace>`；
- 最多 200 个请求；
- 每条 trace：tool/input/output/ms/success。

### AgentMonitor

- 请求数、成功/失败、成功率、平均耗时；
- 各角色调用数；
- 接口 `GET /api/agent/monitor`。

---

## 12. 五个完整例子

### 例子 1：附近1公里有哪些医院

请求：

```json
{"question":"附近1公里有哪些医院","sessionId":"demo"}
```

流程：

```
rewrite：无上下文，不改
intent：QUERY / scene=NEARBY / category=医院 / radius=1000
route：SPATIAL_QUERY
profile：llmEnabled=false → 确定性
SpatialQueryAgent：
  resolvePlace(null) → default_center
  tools：default_center → pois_within(121.47,31.23,1000,医院,10)
verify：有工具证据，通过
```

返回（节选）：

```json
{
  "intent": "QUERY",
  "agentType": "SPATIAL_QUERY",
  "engine": "tool-registry",
  "toolsUsed": ["default_center", "pois_within"],
  "latencyMs": 22,
  "answer": "【当前区域中心】附近1000米内找到 10 个医院POI。最近的是【第九人民医院黄浦区分院】（252.0 米）。",
  "steps": [
    {"tool":"default_center","output":"ok","ms":20},
    {"tool":"pois_within","output":"10 results","ms":2}
  ]
}
```

### 例子 2：黄浦区各类POI有多少

```json
{"question":"黄浦区各类POI有多少","sessionId":"demo"}
```

```
intent：QUERY / scene=DISTRICT_STATS / place=黄浦区
route：SPATIAL_QUERY
preferDistrict=true → district_center(黄浦区)
tools：district_center → district_stats(nameOrCode=黄浦区)
answer：【黄浦区】共有 2772 个 POI。
```

### 例子 3：那餐厅呢（多轮）

```json
{"question":"那餐厅呢","sessionId":"demo"}
```

```
上下文：place=黄浦区, radius=1000
rewrite：在黄浦区附近 那餐厅呢
intent：QUERY / scene=NEARBY / place=黄浦区 / category=餐饮
SpatialQueryAgent：
  looksLikeDistrict("黄浦区") → district_center
  tools：district_center → pois_within(黄浦区中心,1000,餐饮,10)
answer：【黄浦区】附近1000米内找到 10 个餐饮POI。最近的是【普明面馆】（225.0 米）。
```

### 例子 4：从外滩到陆家嘴怎么走

```json
{"question":"从外滩到陆家嘴怎么走","sessionId":"demo"}
```

```
intent：ROUTE / from=外滩 / to=陆家嘴
route：ROUTE_PLANNING
RoutePlanningAgent：
  resolvePoint(外滩) → search_place
  resolvePoint(陆家嘴) → search_place
  tools：route_compare(fromLon,fromLat,toLon,toLat,modes=[car,walk],alternatives=3)
  RoutePlanService → pgr_ksp → 3 驾车 + 3 步行
answer：已为你规划 3 条驾车路线和 3 条步行路线...
routePlanUrl：/route.html?...
```

### 例子 5：生成空间诊断报告

```json
{"question":"生成空间诊断报告","sessionId":"demo"}
```

```
intent：REPORT
route：REPORT
ReportAgent → ReportService：
  读上下文（place/radius）
  查 district_ranking + buffer_stats
  LLM 可用 → Markdown；否则模板
返回 Markdown 报告
```

---

## 13. 失败与回退矩阵

| 场景 | 行为 |
|---|---|
| LLM 没配置 | 直接走确定性 |
| LLM 调用异常 | catch → 确定性 |
| 工具不存在 | `ToolResult.fail("unknown tool")` |
| 工具不在角色白名单 | `ToolResult.fail("tool not allowed")` |
| 缺必填参数 | `ToolResult.fail("missing arg")` |
| 工具超时 | `ToolResult.fail("tool timeout")` |
| 答案为空 | BaseAgent 兜底“没有找到…” |
| QUERY/ROUTE 无工具证据 | AnswerVerifier 改成“没有获取到空间数据” |
| 答案含 NaN/Infinity | AnswerVerifier 改成“结果包含无效数值” |
| 无上下文 | rewrite 不改写 |
| 记忆重启丢失 | 当前已知不足；生产换 Redis |

---

## 14. 面试问答

**Q：Agent 的整体流程？**

入口 → Orchestrator → 改写 → 意图 → 路由 → 角色 → 工具 → 校验 → 汇总 → 记忆 → 轨迹/监控。

**Q：为什么只保留 4 大类意图？**

顶层意图只负责路由；查询内部用 `scene` 区分；避免意图爆炸。

**Q：Agent 秒回是不是没调用？**

不是。空间查询走确定性 SQL 工具，22ms 正常；UI 显示 intent/agentType/tools/latency，trace 可查。

**Q：怎么防幻觉？**

工具返回真实数据；AnswerVerifier 检查空答案/无工具证据/NaN；提示词禁止编造。

**Q：多轮记忆怎么实现？**

sessionId → ConversationState，最近 12 条 + 上下文；追问改写补地点/分类。

**Q：为什么复杂空间意图走确定性？**

比较/路线/排名对正确性要求高；SQL 可复现、可解释。

**Q：新增工具/角色要改什么？**

工具：`ToolRegistry.registerTools` 加定义 + handler；角色：枚举 + profiles.json + BaseAgent 子类。
