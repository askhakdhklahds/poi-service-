-- 步行优先路网 v2：
-- 1) 去掉 path / track / cycleway，减少穿小区、穿建筑的非正式路径；
-- 2) 对 service 巷道、主干道加惩罚，优先人行道/居住区道路；
-- 3) 仍保留 steps，台阶速度 0.8 m/s。
-- 若该网络不连通，RoutePlanService 会回退 road_walk / road_walk_all。

DROP TABLE IF EXISTS road_walk_v2;
CREATE TABLE road_walk_v2 AS
WITH base AS (
    SELECT id,
           source,
           target,
           name,
           highway,
           st_length(geom::geography) AS length_m,
           st_length(geom::geography)
               / (CASE WHEN highway = 'steps' THEN 0.8 ELSE 1.2 END) AS base_cost,
           geom
    FROM road_main
    WHERE highway IN (
        'footway', 'pedestrian', 'steps',
        'residential', 'living_street', 'service',
        'unclassified', 'tertiary', 'secondary', 'primary'
    )
      AND (access IS NULL OR access NOT IN ('private', 'no'))
)
SELECT id,
       source,
       target,
       name,
       highway,
       length_m,
       base_cost * (CASE highway
           WHEN 'footway' THEN 1.0
           WHEN 'pedestrian' THEN 1.0
           WHEN 'residential' THEN 1.1
           WHEN 'living_street' THEN 1.1
           WHEN 'service' THEN 1.5
           WHEN 'unclassified' THEN 1.2
           WHEN 'tertiary' THEN 1.3
           WHEN 'secondary' THEN 1.5
           WHEN 'primary' THEN 1.8
           WHEN 'steps' THEN 1.5
           ELSE 1.2
       END) AS cost,
       base_cost * (CASE highway
           WHEN 'footway' THEN 1.0
           WHEN 'pedestrian' THEN 1.0
           WHEN 'residential' THEN 1.1
           WHEN 'living_street' THEN 1.1
           WHEN 'service' THEN 1.5
           WHEN 'unclassified' THEN 1.2
           WHEN 'tertiary' THEN 1.3
           WHEN 'secondary' THEN 1.5
           WHEN 'primary' THEN 1.8
           WHEN 'steps' THEN 1.5
           ELSE 1.2
       END) AS reverse_cost,
       geom
FROM base;

ALTER TABLE road_walk_v2 ADD PRIMARY KEY (id);
CREATE INDEX idx_road_walk_v2_source ON road_walk_v2 (source);
CREATE INDEX idx_road_walk_v2_target ON road_walk_v2 (target);
CREATE INDEX idx_road_walk_v2_geom ON road_walk_v2 USING GIST (geom);
ANALYZE road_walk_v2;
