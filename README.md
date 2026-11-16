# 城市 POI 空间分析服务平台（v2 企业级）

面向 GIS 后端求职的完整项目：**85,864 条真实上海 OSM POI + 16 个真实行政区边界 + 1000 万条压测数据** + 空间查询/分析 + 异步导入 + Redis 缓存 + 物化视图 + GeoServer 服务发布 + 监控。

## 数据来源与许可

| 数据 | 来源 | 数量 | 许可 |
|---|---|---|---|
| 上海 POI | OpenStreetMap（OSM France 上海提取） | **85,864 条真实 POI** | © OpenStreetMap contributors，ODbL 1.0 |
| 行政区边界 | 阿里云 DataV | 16 个真实区 | 注明来源 |
| 压测数据 | 程序生成，仅用于 `poi_bench` | 10,010,000 条合成点 | 非真实业务数据 |
| 人口网格 | WorldPop 2020 中国 1km | 15,237 个网格（9,422 个在区内），按七普 24,870,895 校准 | CC-BY-4.0 |
| 底图 | Esri World Street Map | — | © Esri |

> 完整来源、原始文件、处理脚本和验证 SQL 见 `../数据来源与项目说明.md`。
> 演示/业务查询用真实 `poi` 表；1000 万 `poi_bench` 只做规模压测，两者不要混着说。

## 文档

完整架构、代码走读、12 篇教程和简历包装见 [`wiki/文档中心.md`](wiki/文档中心.md)。

## 能力矩阵

| 能力 | 实现 | 对应企业场景 |
|---|---|---|
| 空间数据建模 | `4326 存储 + 4549 米制投影` 双列 | 多坐标系数据统一 |
| 空间查询 | `ST_DWithin` 周边、`<->` KNN | 附近搜索/最近设施 |
| 空间分析 | 缓冲区统计、行政区归属 | 商圈分析/设施分布 |
| 大数据量 | 1000 万 POI + GiST + 物化视图 | 城市级数据平台 |
| 数据入库 | CSV 异步导入 + `COPY` + 幂等 | ETL/数据治理 |
| 缓存 | Redis（Memurai）+ Caffeine | 热点统计/瓦片缓存 |
| 地图服务 | MVT 自研瓦片 + GeoServer WMS/WFS | 矢量瓦片/标准 OGC |
| 可观测 | Actuator health/metrics | 运维监控 |
| 部署 | Dockerfile + Docker Compose | 容器化交付 |

## 架构

```
MapLibre / Leaflet / QGIS
        │  MVT / WMS / REST
┌───────▼────────────────────────────────────┐
│ Spring Boot 3.4 (Java 17)                  │
│  PoiController / AnalysisController        │
│  DistrictController / TileController       │
│  ImportController / AdminController        │
│  ├─ Redis 缓存（统计结果）                  │
│  ├─ Caffeine（MVT 瓦片）                    │
│  └─ GeoServer REST 自动发布                 │
└───────┬────────────────────────────────────┘
        │ JDBC
┌───────▼────────────────────────────────────┐
│ PostgreSQL 17 + PostGIS 3.6                │
│  poi（1000 万，4326 + 4549 + GiST）         │
│  district（16 个区，真实边界）              │
│  mv_district_stats（物化视图）              │
│  import_task / poi_staging                 │
└────────────────────────────────────────────┘
```

## 技术栈

- Java 17、Spring Boot 3.4.5、Spring JDBC
- PostgreSQL 17.11 + PostGIS 3.6.2 + pgRouting 4.0
- Valhalla 路由服务（高精度路线，可配置/可回退本地）
- Redis 兼容服务 Memurai 4.1（端口 6379）
- Caffeine 3.1（MVT 瓦片本地缓存）
- GeoServer 3.0.2（WMS/WFS）
- Leaflet 1.9（演示页）
- Flyway（版本化建表）
- Actuator（健康检查/指标）

## 目录

```
poi-service/
├── build.bat / run.bat
├── Dockerfile / docker-compose.yml
├── scripts/
│   ├── data/shanghai_districts.json   # 真实上海行政区边界
│   ├── import_districts.sql
│   ├── seed_100k.sql / seed_1m.sql / seed_10m.sql
│   └── benchmark.sql                  # GiST 前后对比
└── src/main/java/com/example/poi/
    ├── config/     Cache / TileCache / Async
    ├── controller/ Poi / Analysis / District / Tile / Import / Admin
    └── service/    Poi / District / Import / ImportBenchmark / GeoServer
```

## 快速启动

```cmd
:: 1. 确认 PostgreSQL 与 Memurai 已运行
pg_ctl -D D:\gis-env\pgsql\data status
"D:\Program Files\Memurai\memurai-cli.exe" ping

:: 2. 打包启动
build.bat
run.bat
```

服务地址：<http://localhost:8081/>（演示页）
健康检查：<http://localhost:8081/actuator/health>

## 初始化数据

### 行政区（真实数据，16 个区）

```cmd
scripts\import_districts.bat
```

### 真实 POI（OSM，85,864 条）

```cmd
:: 1. 下载上海 OSM 提取（仓库已包含 scripts\data\shanghai-latest.osm.pbf）
::    https://download.openstreetmap.fr/extracts/asia/china/shanghai-latest.osm.pbf

:: 2. 导入点要素
"C:\Program Files\QGIS 3.44.15\bin\ogr2ogr.exe" -f PostgreSQL ^
  "PG:host=127.0.0.1 port=5432 dbname=poi user=postgres password=postgres" ^
  "scripts\data\shanghai-latest.osm.pbf" points ^
  -oo CONFIG_FILE="scripts\osmconf_poi.ini" ^
  -nln osm_points -lco GEOMETRY_NAME=geom -nlt POINT -overwrite

:: 3. 生成真实 POI 表并切换为主表
psql -h 127.0.0.1 -U postgres -d poi -f scripts\load_osm_poi.sql
psql -h 127.0.0.1 -U postgres -d poi -f scripts\switch_to_real_poi.sql
```

> 切换后：`poi` = **85,864 条真实 OSM POI**，`poi_bench` = **1000 万条压测数据**。

### 1000 万压测数据（poi_bench）

```cmd
set PGPASSWORD=postgres
psql -h 127.0.0.1 -U postgres -d poi -f scripts\seed_10m.sql
```

### 物化视图（行政区统计）

```bash
curl -X POST http://localhost:8081/api/admin/refresh-stats
```

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/poi/within?lon=&lat=&radius=&category=&limit=` | 周边查询 |
| GET | `/api/poi/nearest?lon=&lat=&limit=` | 最近邻（KNN） |
| GET | `/api/poi/search?keyword=&limit=` | 地名搜索（POI + 行政区，路线页自动定位） |
| GET | `/api/poi/{id}` | POI 详情（含所属区） |
| GET | `/api/poi/count` | 真实 POI 总数 |
| GET | `/api/poi/stats` | 大类/小类统计 |
| GET | `/api/poi/geojson?limit=` | GeoJSON（采样/全量） |
| GET | `/api/analysis/buffer?lon=&lat=&radius=` | 缓冲区分类统计 |
| GET | `/api/life-circle/categories` | 生活圈设施分类 |
| GET | `/api/life-circle/coverage?adcode=&category=&threshold=` | 区级设施覆盖评估 |
| POST | `/api/route/plan` | 多路线对比（驾车/步行 + 沿途特点） |
| GET | `/api/district/list` | 行政区列表 |
| GET | `/api/district/{adcode}/stats` | 行政区 POI 统计（Redis 缓存） |
| GET | `/api/tiles/{z}/{x}/{y}.mvt` | 动态 MVT 矢量瓦片（Caffeine 缓存） |
| GET | `/tiles/{z}/{x}/{y}.pbf` | 预生成瓦片（磁盘缓存，7 天 Cache-Control） |
| POST | `/api/import/poi` | 上传 CSV 异步导入 |
| GET | `/api/import/tasks/{id}` | 导入进度 |
| POST | `/api/admin/refresh-stats` | 刷新物化视图 |
| POST | `/api/admin/geoserver/publish` | 自动发布 GeoServer 图层 |
| GET | `/api/admin/tile-cache-stats` | 瓦片缓存命中率 |
| GET | `/api/admin/import-benchmark?rows=&mode=copy\|batch\|row` | 导入方式压测 |
| GET | `/actuator/health` | 健康检查 |
| GET | `/actuator/prometheus` | Prometheus 指标 |
| POST | `/api/auth/login` | 登录获取 JWT |
| GET | `/api/config` | 前端配置（CDN 瓦片地址） |
| GET | `/api/admin/slow-queries` | 慢查询（ADMIN） |
| GET | `/swagger-ui.html` | Swagger UI |
| GET | `/v3/api-docs` | OpenAPI JSON |

示例：

```bash
curl "http://localhost:8081/api/poi/within?lon=121.4737&lat=31.2304&radius=1000&limit=10"
curl "http://localhost:8081/api/poi/nearest?lon=121.4737&lat=31.2304&limit=10"
curl "http://localhost:8081/api/district/310101/stats"
curl -X POST "http://localhost:8081/api/admin/geoserver/publish"

# 导入压测
curl "http://localhost:8081/api/admin/import-benchmark?rows=5000&mode=copy"
curl "http://localhost:8081/api/admin/import-benchmark?rows=5000&mode=batch"
curl "http://localhost:8081/api/admin/import-benchmark?rows=5000&mode=row"
```

## 预生成瓦片（离线缓存）

- 生成：`python scripts/generate_tiles.py`
- 结果：z9–13 共 **708 张 `.pbf`**，约 **16MB**，存于 `tiles/`
- 服务：`GET /tiles/{z}/{x}/{y}.pbf`
  - `Content-Type: application/vnd.mapbox-vector-tile`
  - `Cache-Control: max-age=604800, public`
- 页面：`/all-tiles.html`
- 特点：请求时直接读磁盘，**不查 PostGIS**，适合企业里的“切片 + 缓存”方案

> 动态 MVT（`/api/tiles/...`）每次查库；预生成瓦片（`/tiles/...`）是离线产物。
> 面试时可以对比两者：动态适合频繁变更，预生成适合稳定数据和高并发。

## 高德底图与 GCJ-02 纠偏

高德瓦片是 **GCJ-02（火星坐标）**，业务数据是 **WGS84**，直接叠加会偏移约 500 米。
本项目单独生成了一套纠偏数据：

- `scripts/build_gcj_data.py`：WGS84 → GCJ-02，生成 `poi_gcj` 和 `districts_gcj.json`
- `scripts/generate_tiles.py poi_gcj tiles-gcj`：生成纠偏瓦片
- `GET /tiles-gcj/{z}/{x}/{y}.pbf`：提供与高德对齐的瓦片
- 页面 `/all-tiles.html`：高德街道/卫星底图 + 纠偏瓦片 + 分类图标

## Agent 架构（EchoMind 精简版）

- `agent/orchestrator`：`AgentOrchestrator`、`AgentType`、`AgentRequest/Response`、`AgentProfile/AgentProfiles`
- `agent/role`：`BaseAgent` + `GeneralAgent` / `SpatialQueryAgent` / `RoutePlanningAgent` / `ReportAgent`
- `agent/intent`：`IntentRecognizer`（关键词 + 可选 LLM）
- `agent/tool`：`ToolRegistry`（白名单、必填参数、超时、统计）
- `agent/memory`：`ConversationStore`（最近 12 条 + 追问改写）
- `agent/verify`、`agent/compose`、`agent/trace`、`agent/monitor`
- 角色配置：`src/main/resources/agents/profiles.json`
- 接口：`POST /api/agent/ask`、`POST /api/agent/report`、`GET /api/agent/monitor`、`GET /api/agent/trace/{requestId}`
- 顶层意图只保留 4 类：`QUERY`（查询/统计/排名/对比）、`ROUTE`（路线）、`REPORT`（报告）、`GENERAL`（兜底）；查询内部用 `entities.scene` 区分附近/最近/统计/排名/对比。
- 路线工具：`route_compare`（多路线 + 车/人 + 沿途特点）。

设计原则：**LLM 负责理解与表达，空间计算交给确定性 SQL 工具**。

## 15 分钟生活圈评估

- 数据：WorldPop 2020 中国 1km → `population_grid`（15,237 个网格，9,422 个在区内），按 2020 年七普 24,870,895 人统一校准。
- 生成：`python scripts/worldpop_to_grid.py --src scripts/data/chn_pop_2020_1km.tif --out scripts/data/pop_grid_1km.csv --factor 1`
- 导入：`psql -h 127.0.0.1 -U postgres -d poi -f scripts/load_population_grid.sql`
- 分析：`ST_DWithin` 按 1.2 m/s 步行速度折算半径，输出覆盖人口、覆盖率、盲区人口和网格 FeatureCollection。
- 接口：`GET /api/life-circle/categories`、`GET /api/life-circle/coverage?adcode=&category=&threshold=`
- 页面：`/life-circle.html`
- 实测：黄浦区医疗 15 分钟覆盖率 97.2%，浦东新区医疗 31.9%。

## 多路线对比（车/人）

- **高精度路线**：`ValhallaRouteService` 调公共 Valhalla（`https://valhalla1.openstreetmap.de`），`costing=auto|pedestrian`，支持备选路线；配置 `route.valhalla.enabled/base-url/timeout-seconds`，默认 8s 超时。
- **本地兜底**：Valhalla 失败/超时自动回退 `pgr_ksp`；`road_car` 81,481 条边（按 maxspeed/道路等级算时间，oneway 控制方向）、`road_walk_v2` 76,824 条边（排除 path/track，优先人行道/居住区道路）、`road_walk` / `road_walk_all` 作为回退路网。
- **算法**：`pgr_ksp` 每个模式取多条候选，按边集合 Jaccard 去重，最多 3 条驾车 + 3 条步行；Valhalla 路线同样做 150m POI / 行政区富化。
- **路径特点**：距离、预计时间、道路类型构成、150m 缓冲内沿途 POI、经过行政区、摘要；路线带 `source/sourceLabel`。
- **接口**：`POST /api/route/plan`。
- **页面**：`/route.html`；支持**输入地名自动定位**（`/api/poi/search`，含 POI 与行政区，下拉建议去重）或地图点选；路线加粗（线宽 8 / 选中 10），车/人不同配色，步行用虚线。
- **解析**：支持 `从A到B`、`A到B`、`A→B` 三种写法；路线页显示“起点/终点”文字标签、路线总览和每条路线的距离+预计时间。
- **实测**：人民广场→外滩，Valhalla 驾车 2.9km/11min、步行 2.0km/27min，3+3 条路线；接口冷启动 1.1–2.3s，Redis 命中约 60ms。
- **说明**：时间未考虑实时路况/红绿灯/转向限制；公共 Valhalla 实例可能限流，生产应自建；步行路网不连通时回退到全市路网，并在路线卡片标注。

## 前端界面

- 底图：WGS84 页面（`/`、`/points.html`、`/mvt.html`、`/life-circle.html`、`/route.html`）使用 Esri `World_Imagery`，地图 `maxZoom=18`；高德页面（`/agent.html`、`/all-tiles.html`）`maxzoom=18`。
- POI 分类：餐饮红、购物橙、医院深红、学校紫、交通蓝、景点绿、商业粉、其他灰；`agent.html`、`index.html`、`points.html`、`all-tiles.html` 都有图例。
- POI 显隐：`/all-tiles.html` 和 `/agent.html` 提供“显示/隐藏 POI”开关。
- 首页：五个入口改成快捷卡片；空间助手重新设计，移除“自然语言提问”文案。
- Agent：每条回答显示 `intent / agentType / engine / toolsUsed / latencyMs`，执行轨迹默认展开，并提供“查看工具轨迹”链接。
- 生活圈：影像底图 + 绿色覆盖格 + 红色盲区格 + 摘要卡片。
- 多路线：6 条路线多色绘制 + 路线卡片 + 高亮。
- 截图：`shot_agent_ui.png`、`shot_basemap_z18.png`、`shot_life_circle_cdp.png`、`shot_route_cdp.png`、`shot_route_page.png`、`shot_index_ui.png`、`shot_all_tiles_toggle.png`、`shot_agent_toggle.png`。

## 大类与小类

- `poi.sub_category` 由 OSM 标签映射（`scripts/add_sub_category.sql`），共 38 个大类/小类组合
- `/api/poi/stats` 同时返回 `categories` 和 `subCategories`
- 前端支持大分类勾选 + 小类下拉筛选
- 分类图标由 `scripts/generate_icons.py` 生成（8 个 PNG）

## 工程化

- **Swagger**：`/swagger-ui.html`，`/v3/api-docs`
- **统一异常**：`GlobalExceptionHandler` 返回 `{code, message}`
- **静态瓦片**：`/tiles/{z}/{x}/{y}.pbf`、`/tiles-gcj/{z}/{x}/{y}.pbf`，7 天 Cache-Control
- **地图交互**：点击 POI 出详情面板、大类/小类筛选、统计、图例、高德街道/卫星、聚合点
- **前端页面**：`/all-tiles.html`（高德 + GCJ 瓦片）、`/points.html`（GeoJSON）、`/mvt.html`（动态 MVT）
- **登录页**：`/login.html`

## 认证与 RBAC

演示账号（生产请改为数据库/LDAP + 环境变量密钥）：

| 用户 | 密码 | 角色 |
|---|---|---|
| `admin` | `admin123` | `ROLE_ADMIN` |
| `analyst` | `analyst123` | `ROLE_ANALYST` |
| `viewer` | `viewer123` | `ROLE_VIEWER` |

```bash
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# 之后带 Authorization: Bearer <token>
```

权限规则：

- `/api/admin/**` → `ROLE_ADMIN`
- `/api/import/**` → `ROLE_ADMIN` 或 `ROLE_ANALYST`
- 读接口（`/api/poi/**`、`/api/district/**`、`/api/analysis/**`）公开，方便地图演示
- 生产：`AUTH_JWT_SECRET` + 数据库用户表/LDAP

## 限流

- Bucket4j，默认每个 IP **120 请求/分钟**
- 配置：`rate-limit.enabled`、`rate-limit.capacity`、`rate-limit.period-minutes`
- 超限返回 `429 {"code":429,"message":"too many requests"}`

## 测试

- `mvn test`：`PoiController` MockMvc + `JwtService` 单测；21 通过、1 跳过
- `PoiApiIntegrationTest`：Testcontainers 启动 PostGIS，验证 count/within/JWT/RBAC
- `python scripts/test_100_cases.py`：**139 个真实数据用例**，覆盖数据完整性、空间查询、生活圈、路线、Agent、工程安全；HTTP 与 SQL 交叉验证，报告见 `真实数据测试报告-100例.md`
- `python scripts/eval_geocoding.py`：**200 条真实 POI 地名检索评测**，Top-1 80.5% / Top-3 88.0%（50m），截断输入 Top-3 87.0%（200m），报告见 `地名检索准确率评测.md`
- `python scripts/eval_agent_1000.py`：**1,000 条真实 POI Agent 评测**（400 周边 + 400 最近邻 + 20 路线 + 180 多轮），意图 99.8%、工具选择 99.3%、剔除脏点后端到端 94.1%、P95 44ms，报告见 `Agent真实数据评测报告-1000例.md`
- `python scripts/test_30_cases.py`：30 个功能用例 + 性能/安全附录
- Docker 不可用时 Testcontainers 测试自动跳过；CI（GitHub Actions 有 Docker）会执行
- `mvn verify` 跑完整构建 + 测试

## 可观测

- 健康检查：`/actuator/health`
- Prometheus：`/actuator/prometheus`
- 自定义指标：`poi_tile_cache_hit_rate`、`poi_tile_cache_hits`、`poi_tile_cache_misses`
- 慢查询：`GET /api/admin/slow-queries`（ADMIN，需 `pg_stat_statements`）
- 监控栈：`docker compose up` 后
  - Prometheus：http://localhost:9090
  - Grafana：http://localhost:3000（admin/admin）
  - 仪表盘：`ops/grafana/dashboards/poi-service.json`

> 本机 Docker 守护进程不可用，监控栈需要启动 Docker Desktop 后验证。

## CI/CD

- `.github/workflows/ci.yml`（仓库根目录）
- `mvn -B verify` + Docker 多阶段构建
- main 分支推送镜像到 GHCR
- 使用前需要把项目推到 GitHub

## CDN / OSS

- `/api/config` 返回 `tileBaseUrl`、`gcjTileBaseUrl`
- 上传脚本：`scripts/upload_tiles_to_cdn.sh`
- 部署说明：`ops/cdn/README.md`
- 环境变量：
  ```bash
  TILES_PUBLIC_BASE_URL=https://cdn.example.com/poi-tiles
  TILES_PUBLIC_GCJ_BASE_URL=https://cdn.example.com/poi-tiles-gcj
  ```
- 配置后 `all-tiles.html` 自动切换到 CDN 瓦片源

## 实测结果（本机，2026-11-11）

### 数据集说明

| 表 | 数据量 | 用途 |
|---|---|---|
| `poi` | **85,864 条真实 OSM POI**（上海） | 演示 / 业务查询 / GeoServer |
| `poi_bench` | **10,010,000 条合成 POI** | 规模压测 |
| `district` | 16 个真实上海行政区 | 空间统计 |

硬件：Intel i5-14600KF / 32GB RAM / Windows 11 Pro
数据库：PostgreSQL 17.11 + PostGIS 3.6.2 单机
缓存：Memurai 4.1（兼容 Redis 7.2 协议）

### 真实数据（85,864 条）

| 场景 | 结果 |
|---|---|
| 周边接口（串行 50 次） | P50 **4.1 ms** / P95 **8.5 ms** / P99 10.4 ms |
| 黄浦区 POI 统计 | **2,772 条** |
| MVT z13 | **49.7 KB**，31 ms |
| 同范围 GeoJSON | **227 KB** |
| 体积下降 | **约 78.6%** |
| GeoServer WFS | `totalFeatures=85,864` |

### 1000 万压测数据（poi_bench）

| 场景 | 结果 |
|---|---|
| `ST_DWithin` 无索引 count | **690 ms**，Parallel Seq Scan |
| `ST_DWithin` + GiST count（冷） | **54.6 ms**，Bitmap Index Scan |
| `ST_DWithin` + GiST count（热） | **约 22 ms** |
| `ST_Distance < 1000`（反例） | **879 ms**，Parallel Seq Scan |
| KNN `<->` | **0.37 ms**，Index Scan |
| 周边接口（串行 50 次） | P50 **34.5 ms** / P95 **50.9 ms** / P99 146 ms |
| 最近邻接口（串行 50 次） | P50 **4.1 ms** / P95 **5.0 ms** |
| JMeter 50 并发 × 20 次 | **181 QPS**，P50 29 ms / P95 **43 ms** / P99 138 ms，0 错误 |

### 物化视图 + Redis（行政区统计）

| 场景 | 结果 |
|---|---|
| 实时 JOIN + 聚合（黄浦区，10M） | **220 ms** |
| 物化视图查询 | **0.024 ms** |
| 物化视图全量构建（10M） | 4 分 6 秒 |
| 接口冷启动（物化视图 + 写缓存） | **58 ms** |
| 接口命中 Redis | **25 ms** |

> 物化视图构建成本高，适合定时/低峰刷新；接口层再叠加 Redis 缓存。

### 导入方式对比（5 万行，1000 万表上测得）

| 方式 | 耗时 | 吞吐 |
|---|---|---|
| 逐条 INSERT（row） | **14.7 s** | 3390 行/秒 |
| JDBC Batch（batch） | **6.8 s** | 7316 行/秒 |
| COPY + staging（copy） | **9.8 s** | 5077 行/秒 |

> 本实现中 COPY 多了一次 staging → 正式表的转换，因此略慢于 JDBC Batch；真实结论是「批量远快于逐条」，而不是 COPY 一定最快。这是面试时很好的深入讨论点。

> **2026-11-16 本机复测**：`ST_DWithin` 无索引约 1.19s、GiST 热缓存 24–139ms（中位约 27ms）；KNN 0.177ms；行政区实时聚合热缓存 73–76ms、物化视图 0.014ms；5 万行导入 row 11.3–16.3s / batch 3.9–4.2s / copy 2.7s；MVT 同范围 GeoJSON（含属性）约 248KB → 约 50KB。性能和缓存、并行度、机器负载有关，面试以复测区间为准。

### MVT 对比（1000 万压测数据）

| 场景 | 结果 |
|---|---|
| MVT z13（≤2 万要素） | **856 KB**，冷 138 ms / 热 **4.8 ms** |
| 同范围 GeoJSON | **3387 KB** |
| 体积下降 | **约 75%** |

> 真实数据（85,864 条）下同一瓦片为 **49.7 KB**，GeoJSON 为 **227 KB**，下降约 **78.6%**。
> GeoServer 自动发布：workspace `poi`、datastore `poi_pg`、图层 `poi:district` / `poi:poi`；WMS 200，WFS `totalFeatures=85,864`。
> 所有数字为本机实测；面试时如实说明数据量、单机环境和请求方式。

## 压测复现

```cmd
:: SQL 索引对比
psql -h 127.0.0.1 -U postgres -d poi -f scripts\benchmark.sql

:: 接口延迟（PowerShell）
1..50 | % { (Measure-Command { Invoke-WebRequest "http://localhost:8081/api/poi/within?lon=121.4737&lat=31.2304&radius=1000&limit=200" | Out-Null }).TotalMilliseconds }
```

## 面试讲法（要点）

1. 为什么 4326 存一份、4549 再存一份：避免每次查询 `ST_Transform`。
2. 为什么用 `ST_DWithin` 而不是 `ST_Distance < x`：前者能用 GiST。
3. GiST 是 filter-and-refine：先包围盒粗筛，再精确判断。
4. 最近邻用 `<->` 走 KNN 索引，不是全表排序。
5. MVT 只返回视口数据，体积远小于 GeoJSON。
6. Redis 缓存统计结果，冷热差异约 15 倍。
7. 导入用 COPY + staging + `ON CONFLICT`，兼顾吞吐和幂等。
8. GeoServer 通过 REST 自动发布，避免手工点界面。
9. 所有数字都来自本机实测，能说清数据量、环境和测法。

完整面试手册：`../求职项目-城市POI空间分析平台.md`
