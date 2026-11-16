-- 一次性切换：上海真实 POI -> poi_shanghai，湖北真实 POI -> poi
-- 注意：只需执行一次

ALTER TABLE poi RENAME TO poi_shanghai;
ALTER TABLE poi_hubei RENAME TO poi;

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
SELECT 'poi_shanghai', count(*) FROM poi_shanghai
UNION ALL
SELECT 'poi_bench', count(*) FROM poi_bench;
