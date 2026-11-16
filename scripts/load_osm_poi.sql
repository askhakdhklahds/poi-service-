-- 从 osm_points 中筛选真实 POI，写入 poi_real
DROP TABLE IF EXISTS poi_real;

CREATE TABLE poi_real (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       text NOT NULL,
    category   text NOT NULL,
    lng        double precision NOT NULL,
    lat        double precision NOT NULL,
    geom       geometry(Point, 4326) NOT NULL,
    geom_4549  geometry(Point, 4549),
    source_id  text UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO poi_real (name, category, lng, lat, geom, geom_4549, source_id)
SELECT COALESCE(NULLIF(name, ''), '未命名') AS name,
       CASE
           WHEN amenity IN ('restaurant','fast_food','cafe','food_court','bar','pub','biergarten') THEN '餐饮'
           WHEN amenity IN ('hospital','clinic','pharmacy','doctors','dentist') OR healthcare IS NOT NULL THEN '医院'
           WHEN amenity IN ('school','university','college','kindergarten','library','driving_school') THEN '学校'
           WHEN amenity IN ('bus_station','taxi','parking','parking_space','fuel','charging_station')
                OR public_transport IS NOT NULL
                OR highway = 'bus_stop' THEN '交通'
           WHEN shop IS NOT NULL THEN '购物'
           WHEN tourism IS NOT NULL OR leisure IS NOT NULL OR historic IS NOT NULL THEN '景点'
           WHEN office IS NOT NULL OR craft IS NOT NULL THEN '商业'
           ELSE '其他'
       END AS category,
       ST_X(geom),
       ST_Y(geom),
       geom,
       ST_Transform(geom, 4549),
       'osm_' || osm_id
FROM osm_points
WHERE geom IS NOT NULL
  AND (amenity IS NOT NULL
       OR shop IS NOT NULL
       OR tourism IS NOT NULL
       OR leisure IS NOT NULL
       OR office IS NOT NULL
       OR craft IS NOT NULL
       OR historic IS NOT NULL
       OR healthcare IS NOT NULL
       OR public_transport IS NOT NULL
       OR highway = 'bus_stop');

CREATE INDEX idx_poi_real_geom      ON poi_real USING GIST (geom);
CREATE INDEX idx_poi_real_geom_4549 ON poi_real USING GIST (geom_4549);
CREATE INDEX idx_poi_real_category  ON poi_real (category);
CREATE INDEX idx_poi_real_cat_4549  ON poi_real (category, geom_4549);

ANALYZE poi_real;

SELECT category, count(*) AS cnt
FROM poi_real
GROUP BY category
ORDER BY cnt DESC;
