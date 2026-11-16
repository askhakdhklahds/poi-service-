-- 生成 1000 万条 POI（上海范围），已有数据自动跳过
-- 预计 5–15 分钟，视磁盘性能而定
INSERT INTO poi_bench (name, category, lng, lat, geom, geom_4549, source_id)
SELECT 'poi_' || g,
       (ARRAY['餐饮', '购物', '医院', '学校', '交通', '景点'])[1 + floor(random() * 6)],
       lng,
       lat,
       ST_SetSRID(ST_MakePoint(lng, lat), 4326),
       ST_Transform(ST_SetSRID(ST_MakePoint(lng, lat), 4326), 4549),
       'seed_' || g
FROM (
    SELECT g,
           121.0 + random() * 0.9 AS lng,
           30.9  + random() * 0.6 AS lat
    FROM generate_series(1, 10000000) g
) t
ON CONFLICT (source_id) DO NOTHING;

ANALYZE poi_bench;

SELECT count(*) AS total FROM poi_bench;
