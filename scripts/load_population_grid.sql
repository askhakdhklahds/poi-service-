-- 在 poi 库执行：
-- psql -h 127.0.0.1 -U postgres -d poi -f scripts/load_population_grid.sql

TRUNCATE population_grid_staging;

\copy population_grid_staging(grid_x, grid_y, lon, lat, pop) FROM 'D:/jue/gis/poi-service/scripts/data/pop_grid_1km.csv' CSV HEADER

TRUNCATE population_grid RESTART IDENTITY;

INSERT INTO population_grid
    (grid_x, grid_y, cell_size_m, lon, lat, pop, district_adcode, geom, geom_4549)
SELECT s.grid_x,
       s.grid_y,
       1000,
       s.lon,
       s.lat,
       s.pop,
       d.adcode,
       ST_SetSRID(ST_MakePoint(s.lon, s.lat), 4326),
       ST_Transform(ST_SetSRID(ST_MakePoint(s.lon, s.lat), 4326), 4549)
FROM population_grid_staging s
LEFT JOIN district d
       ON d.adcode IS NOT NULL
      AND ST_Contains(d.geom, ST_SetSRID(ST_MakePoint(s.lon, s.lat), 4326))
WHERE s.pop > 0;

-- WorldPop 1km 旧版是未约束估计，上海区县合计约 3220 万；
-- 按 2020 年七普上海常住人口 24,870,895 做统一校准。
-- 统一缩放不改变覆盖率，只校准人口绝对值口径。
WITH total AS (
    SELECT sum(pop) AS raw_pop
    FROM population_grid
    WHERE district_adcode IS NOT NULL
)
UPDATE population_grid g
SET pop = g.pop * (24870895.0 / t.raw_pop)
FROM total t
WHERE g.district_adcode IS NOT NULL
  AND t.raw_pop > 0;

ANALYZE population_grid;

SELECT count(*) AS cells,
       round(sum(pop)) AS total_pop_all,
       round(sum(pop) FILTER (WHERE district_adcode IS NOT NULL))
           AS total_pop_in_district,
       count(*) FILTER (WHERE district_adcode IS NOT NULL) AS in_district_cells
FROM population_grid;
