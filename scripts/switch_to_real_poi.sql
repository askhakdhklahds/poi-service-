-- 一次性切换脚本：
--   poi       -> poi_bench（1000 万合成压测数据）
--   poi_real  -> poi（85,864 条真实 OSM POI）
-- 注意：只需执行一次

ALTER TABLE poi RENAME TO poi_bench;
ALTER TABLE poi_real RENAME TO poi;

DROP MATERIALIZED VIEW IF EXISTS mv_district_stats;

CREATE MATERIALIZED VIEW mv_district_stats AS
SELECT d.adcode,
       d.name,
       count(p.id) AS poi_cnt
FROM district d
LEFT JOIN poi p ON ST_Contains(d.geom, p.geom)
GROUP BY d.adcode, d.name;

CREATE UNIQUE INDEX idx_mv_district_stats_adcode
    ON mv_district_stats (adcode);

ANALYZE mv_district_stats;

SELECT 'poi' AS table_name, count(*) AS rows FROM poi
UNION ALL
SELECT 'poi_bench', count(*) FROM poi_bench;
