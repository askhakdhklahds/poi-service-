-- 在 poi 库执行：psql -h 127.0.0.1 -U postgres -d poi -f scripts/benchmark.sql
-- 目的：得到"优化前 vs 优化后"的真实耗时，填进简历

\timing on

-- 1. 优化前：删除 GiST 索引
DROP INDEX IF EXISTS idx_poi_geom_4549;

EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM poi_bench
WHERE ST_DWithin(
    geom_4549,
    ST_Transform(ST_SetSRID(ST_MakePoint(121.4737, 31.2304), 4326), 4549),
    1000
);

-- 2. 优化后：重建 GiST 索引
CREATE INDEX idx_poi_geom_4549 ON poi_bench USING GIST (geom_4549);

EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM poi_bench
WHERE ST_DWithin(
    geom_4549,
    ST_Transform(ST_SetSRID(ST_MakePoint(121.4737, 31.2304), 4326), 4549),
    1000
);

-- 3. ST_Distance < x（索引失效）作为对照
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM poi_bench
WHERE ST_Distance(
    geom_4549,
    ST_Transform(ST_SetSRID(ST_MakePoint(121.4737, 31.2304), 4326), 4549)
) < 1000;

-- 4. 最近邻
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, name
FROM poi_bench
ORDER BY geom_4549 <-> ST_Transform(ST_SetSRID(ST_MakePoint(121.4737, 31.2304), 4326), 4549)
LIMIT 10;
