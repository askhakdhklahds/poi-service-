-- 驾车 / 步行两套路网。
-- cost 单位统一为秒；length_m 为米。
-- 驾车：按 maxspeed 或道路等级默认速度；oneway 控制方向。
-- 步行：按 1.2 m/s，台阶 0.8 m/s；双向。

DROP TABLE IF EXISTS road_car;
CREATE TABLE road_car AS
WITH base AS (
    SELECT id,
           source,
           target,
           name,
           highway,
           oneway,
           st_length(geom::geography) AS length_m,
           COALESCE(
               NULLIF(regexp_replace(maxspeed, '[^0-9].*', ''), '')::numeric,
               CASE highway
                   WHEN 'trunk' THEN 60
                   WHEN 'trunk_link' THEN 50
                   WHEN 'primary' THEN 50
                   WHEN 'primary_link' THEN 40
                   WHEN 'secondary' THEN 40
                   WHEN 'secondary_link' THEN 35
                   WHEN 'tertiary' THEN 30
                   WHEN 'tertiary_link' THEN 25
                   WHEN 'residential' THEN 25
                   WHEN 'living_street' THEN 10
                   WHEN 'service' THEN 15
                   ELSE 25
               END
           ) AS speed_kmh,
           geom
    FROM road_main
    WHERE highway IN (
        'trunk', 'trunk_link',
        'primary', 'primary_link',
        'secondary', 'secondary_link',
        'tertiary', 'tertiary_link',
        'residential', 'service', 'unclassified',
        'living_street', 'road', 'track'
    )
      AND (access IS NULL OR access NOT IN ('private', 'no'))
)
SELECT id,
       source,
       target,
       name,
       highway,
       oneway,
       length_m,
       CASE WHEN oneway = '-1'
            THEN -1
            ELSE length_m / (speed_kmh / 3.6)
       END AS cost,
       CASE WHEN oneway IN ('yes', 'true', '1')
            THEN -1
            ELSE length_m / (speed_kmh / 3.6)
       END AS reverse_cost,
       geom
FROM base;

ALTER TABLE road_car ADD PRIMARY KEY (id);
CREATE INDEX idx_road_car_source ON road_car (source);
CREATE INDEX idx_road_car_target ON road_car (target);
CREATE INDEX idx_road_car_geom ON road_car USING GIST (geom);
ANALYZE road_car;

DROP TABLE IF EXISTS road_walk;
CREATE TABLE road_walk AS
SELECT id,
       source,
       target,
       name,
       highway,
       st_length(geom::geography) AS length_m,
       st_length(geom::geography)
           / (CASE WHEN highway = 'steps' THEN 0.8 ELSE 1.2 END) AS cost,
       st_length(geom::geography)
           / (CASE WHEN highway = 'steps' THEN 0.8 ELSE 1.2 END) AS reverse_cost,
       geom
FROM road_main
WHERE highway IN (
    'footway', 'path', 'pedestrian', 'steps', 'cycleway',
    'residential', 'service', 'living_street', 'unclassified',
    'tertiary', 'secondary', 'primary'
)
  AND (access IS NULL OR access NOT IN ('private', 'no'));

ALTER TABLE road_walk ADD PRIMARY KEY (id);
CREATE INDEX idx_road_walk_source ON road_walk (source);
CREATE INDEX idx_road_walk_target ON road_walk (target);
CREATE INDEX idx_road_walk_geom ON road_walk USING GIST (geom);
ANALYZE road_walk;
