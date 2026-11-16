-- 步行回退路网：可步行路网可能被过滤成多个连通分量，
-- 起终点不连通时回退到全市最大连通分量 road_main。
-- 对快速路/主干道施加 3 倍步行成本，尽量仍走可步行道路。

DROP TABLE IF EXISTS road_walk_all;
CREATE TABLE road_walk_all AS
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
)
SELECT id,
       source,
       target,
       name,
       highway,
       length_m,
       base_cost * (CASE WHEN highway IN
           ('trunk', 'trunk_link', 'primary', 'primary_link') THEN 3 ELSE 1 END) AS cost,
       base_cost * (CASE WHEN highway IN
           ('trunk', 'trunk_link', 'primary', 'primary_link') THEN 3 ELSE 1 END)
           AS reverse_cost,
       geom
FROM base;

ALTER TABLE road_walk_all ADD PRIMARY KEY (id);
CREATE INDEX idx_road_walk_all_source ON road_walk_all (source);
CREATE INDEX idx_road_walk_all_target ON road_walk_all (target);
CREATE INDEX idx_road_walk_all_geom ON road_walk_all USING GIST (geom);
ANALYZE road_walk_all;
