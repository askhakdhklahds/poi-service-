ALTER TABLE poi ADD COLUMN IF NOT EXISTS sub_category text;

UPDATE poi p
SET sub_category = CASE
    WHEN o.amenity IN ('restaurant','fast_food','cafe','bar','pub','food_court','biergarten') THEN
        CASE o.amenity
            WHEN 'restaurant' THEN '餐厅'
            WHEN 'fast_food' THEN '快餐'
            WHEN 'cafe' THEN '咖啡'
            WHEN 'bar' THEN '酒吧'
            WHEN 'pub' THEN '酒吧'
            WHEN 'food_court' THEN '美食广场'
            ELSE '餐饮'
        END
    WHEN o.amenity IN ('hospital','clinic','pharmacy','doctors','dentist') OR o.healthcare IS NOT NULL THEN
        CASE o.amenity
            WHEN 'hospital' THEN '医院'
            WHEN 'clinic' THEN '诊所'
            WHEN 'pharmacy' THEN '药店'
            WHEN 'doctors' THEN '诊所'
            WHEN 'dentist' THEN '牙科'
            ELSE '医疗'
        END
    WHEN o.amenity IN ('school','university','college','kindergarten','library','driving_school') THEN
        CASE o.amenity
            WHEN 'school' THEN '中小学'
            WHEN 'university' THEN '大学'
            WHEN 'college' THEN '学院'
            WHEN 'kindergarten' THEN '幼儿园'
            WHEN 'library' THEN '图书馆'
            ELSE '学校'
        END
    WHEN o.amenity IN ('bus_station','taxi','parking','parking_space','fuel','charging_station')
         OR o.public_transport IS NOT NULL
         OR o.highway = 'bus_stop' THEN
        CASE
            WHEN o.highway = 'bus_stop' THEN '公交站'
            WHEN o.amenity = 'bus_station' THEN '客运站'
            WHEN o.amenity = 'taxi' THEN '出租车站'
            WHEN o.amenity IN ('parking','parking_space') THEN '停车场'
            WHEN o.amenity = 'fuel' THEN '加油站'
            WHEN o.amenity = 'charging_station' THEN '充电站'
            ELSE '交通设施'
        END
    WHEN o.shop IS NOT NULL THEN
        CASE o.shop
            WHEN 'supermarket' THEN '超市'
            WHEN 'convenience' THEN '便利店'
            WHEN 'mall' THEN '商场'
            WHEN 'clothes' THEN '服装'
            WHEN 'bakery' THEN '面包店'
            WHEN 'pharmacy' THEN '药店'
            ELSE '商店'
        END
    WHEN o.tourism IS NOT NULL OR o.leisure IS NOT NULL OR o.historic IS NOT NULL THEN
        CASE
            WHEN o.tourism = 'museum' THEN '博物馆'
            WHEN o.tourism = 'attraction' THEN '景点'
            WHEN o.leisure = 'park' THEN '公园'
            WHEN o.historic IS NOT NULL THEN '历史遗迹'
            ELSE '休闲景点'
        END
    WHEN o.office IS NOT NULL OR o.craft IS NOT NULL THEN
        CASE
            WHEN o.office IS NOT NULL THEN '写字楼'
            ELSE '工坊'
        END
    ELSE '其他'
END
FROM osm_points o
WHERE p.source_id = 'osm_' || o.osm_id;

UPDATE poi SET sub_category = '其他' WHERE sub_category IS NULL;

CREATE INDEX IF NOT EXISTS idx_poi_sub_category ON poi (sub_category);

SELECT category, sub_category, count(*) AS cnt
FROM poi
GROUP BY category, sub_category
ORDER BY category, cnt DESC;
