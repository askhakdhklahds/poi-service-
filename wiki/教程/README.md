# 教程目录

按“先跑起来 → 懂数据 → 懂查询 → 懂地图 → 懂 Agent → 能面试”的顺序学习。

| 章节 | 内容 | 对应代码 |
|---|---|---|
| [01 项目全貌与数据来源](01-项目全貌与数据来源.md) | 项目定位、真实/合成数据、数量口径 | `scripts/`、`README.md` |
| [02 环境与运行](02-环境与运行.md) | PostgreSQL、Memurai、GeoServer、Spring Boot | `application.yml` |
| [03 数据库设计与双列模型](03-数据库设计与双列模型.md) | V1–V4、4326/4549、GiST | `db/migration/` |
| [04 空间查询链路](04-空间查询链路.md) | ST_DWithin、`<->`、ST_Contains | `PoiController`、`PoiService` |
| [05 物化视图与缓存](05-物化视图与缓存.md) | 220ms→0.024ms、Redis | `DistrictService`、`AdminController` |
| [06 MVT 与 GeoServer](06-MVT与GeoServer.md) | ST_AsMVT、预生成瓦片、WMS/WFS、GCJ-02 | `TileController`、`GeoServerService` |
| [07 异步导入](07-异步导入.md) | COPY、staging、幂等、线程池 | `ImportService`、`AsyncConfig` |
| [08 Agent 架构与工具调用](08-Agent架构与工具调用.md) | Orchestrator、Intent、Profiles、ToolRegistry | `agent/` |
| [09 15 分钟生活圈评估](09-15分钟生活圈评估.md) | WorldPop、覆盖率、盲区 | `CoverageService`、`life-circle.html` |
| [10 工程化与监控](10-工程化与监控.md) | JWT、限流、缓存、Prometheus、测试 | `security/`、`config/` |
| [11 性能实测与数字口径](11-性能实测与数字口径.md) | 690→54.6ms、220→0.024ms、QPS | `scripts/benchmark.sql` |
| [12 面试与简历](12-面试与简历.md) | 2 分钟介绍、STAR、追问 | `wiki/简历包装.md` |
| [13 多路线对比](13-多路线对比.md) | Valhalla 高精度 + 本地 pgr_ksp 兜底、沿途特点 | `ValhallaRouteService`、`RoutePlanService`、`route.html` |

## 7 天速成

| 天 | 内容 |
|---|---|
| D1 | 跑起来 + 三个页面 + 接口 |
| D2 | 数据层：表、SRID、GiST、导入 |
| D3 | 查询层：ST_DWithin、KNN、EXPLAIN |
| D4 | 分析层：物化视图、Redis |
| D5 | 地图：MVT、GeoServer、页面 |
| D6 | Agent + 生活圈 |
| D7 | 压测 + 数字 + 2 分钟介绍 |

> 每学一个点，都要能回答：为什么这么做、不用会怎样、怎么测。
