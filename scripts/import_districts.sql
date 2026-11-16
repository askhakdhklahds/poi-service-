-- 将 district_raw（由 ogr2ogr 导入的 DataV GeoJSON）写入正式表
TRUNCATE district RESTART IDENTITY;

INSERT INTO district (adcode, name, geom, geom_4549)
SELECT adcode::text,
       name,
       ST_Multi(ST_CollectionExtract(ST_MakeValid(geom), 3)),
       ST_Transform(ST_Multi(ST_CollectionExtract(ST_MakeValid(geom), 3)), 4549)
FROM district_raw
WHERE geom IS NOT NULL
  AND adcode IS NOT NULL;

SELECT count(*) AS districts,
       count(*) FILTER (WHERE NOT ST_IsValid(geom)) AS invalid
FROM district;
