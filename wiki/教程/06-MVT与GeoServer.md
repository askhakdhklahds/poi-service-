# 06 MVT 与 GeoServer

## 目标

能解释 MVT 为什么比 GeoJSON 小、动态瓦片和预生成瓦片的区别、WMS/WFS 的分工。

## 动态 MVT

`controller/TileController.java`：

```java
String key = z + "/" + x + "/" + y + "/" + category;
byte[] cached = tileCache.getIfPresent(key);
byte[] body = cached != null ? cached : service.tile(z, x, y, category);
```

- Caffeine 缓存键 `z/x/y/category`。
- 响应头 `X-Tile-Cached: HIT/MISS`。
- `Cache-Control: public, max-age=60`。

`service/PoiService.java` 的 `tile`：

```sql
WITH bounds AS (SELECT ST_TileEnvelope(?, ?, ?) AS geom)
SELECT ST_AsMVT(q, 'poi', 4096, 'geom')
FROM (
  SELECT p.id, p.name, p.category,
         ST_AsMVTGeom(ST_Transform(p.geom, 3857), b.geom, 4096, 256, true) AS geom
  FROM poi p, bounds b
  WHERE ST_Intersects(p.geom, ST_Transform(b.geom, 4326))
  LIMIT 20000
) q;
```

参数：

- `extent=4096`：瓦片内坐标精度。
- `buffer=256`：边缘缓冲，防止裁切。
- `clip=true`：裁剪到瓦片。

## 预生成瓦片

- `scripts/generate_tiles.py` 生成 z9–13 共 708 张 `.pbf`。
- `controller/StaticTileController.java` 直接读磁盘，7 天缓存。
- z ≤ 11 做 `ST_SnapToGrid` 聚合，减少要素数。

| 方案 | 适合 |
|---|---|
| 动态 MVT | 数据频繁变化 |
| 预生成 `.pbf` | 数据稳定、高并发、可上 CDN |

## 体积对比

- 真实数据：GeoJSON 227KB → MVT 49.7KB（降 78.6%）。
- 10M 数据：3387KB → 856KB（降约 75%）。

原因：只返回视口数据 + 二进制编码 + 属性名只存一次。

## GCJ-02

高德底图用 GCJ-02，WGS84 直接叠加会偏约 500 米。

**GCJ-02 不是 EPSG 坐标系，`ST_Transform` 处理不了。**

```
poi（WGS84）
  → build_gcj_data.py 偏移
  → poi_gcj + districts_gcj.json
  → generate_tiles.py poi_gcj tiles-gcj
  → 高德底图 + /tiles-gcj/...
```

## GeoServer

`service/GeoServerService.java` 通过 REST 创建：

- workspace `poi`
- datastore `poi_pg`
- 图层 `poi:poi` / `poi:district`

WMS / WFS / WMTS：

| 服务 | 返回 | 用途 |
|---|---|---|
| WMS | 图片 | 只看图 |
| WFS | 矢量要素 | 可查属性、做分析 |
| WMTS | 预切瓦片 | 底图 |

**坑**：`poi` / `district` 有两个几何列，GeoServer 自动算 BBox 可能混用，导致预览空白；解决方式是发布单几何列视图。

## 面试问答

**Q：MVT 为什么小？**

A：只返回当前瓦片 + 二进制 + 属性名只存一次。

**Q：动态 vs 预生成？**

A：动态实时查库，适合变化数据；预生成读磁盘，适合稳定数据和高并发。

**Q：WMS 和 WFS 区别？**

A：WMS 返回渲染好的图片，WFS 返回矢量要素。
