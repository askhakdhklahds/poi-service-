package com.example.poi.agent;

import com.example.poi.service.PoiService;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GisTools {

    private final PoiService poiService;
    private final JdbcTemplate jdbc;

    public GisTools(PoiService poiService, JdbcTemplate jdbc) {
        this.poiService = poiService;
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> searchPlace(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT name, category,
                       ST_X(geom) AS lon, ST_Y(geom) AS lat
                FROM poi
                WHERE name ILIKE ?
                ORDER BY char_length(name), id
                LIMIT 5
                """, "%" + keyword.trim() + "%");
    }

    public List<Map<String, Object>> poisWithin(double lon, double lat, int radius,
                                                String category, int limit) {
        return poiService.within(lon, lat, clampRadius(radius),
                normalizeCategory(category), clampLimit(limit, 500));
    }

    public List<Map<String, Object>> nearestPois(double lon, double lat, int limit,
                                                 String category) {
        return poiService.nearest(lon, lat, clampLimit(limit, 100),
                normalizeCategory(category));
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        if (category.contains("餐") || category.contains("饭") || category.contains("美食")
                || category.contains("咖啡") || category.contains("快餐")) {
            return "餐饮";
        }
        if (category.contains("医院") || category.contains("诊所") || category.contains("药")
                || category.contains("医疗")) {
            return "医院";
        }
        if (category.contains("学校") || category.contains("大学") || category.contains("学院")
                || category.contains("幼儿园")) {
            return "学校";
        }
        if (category.contains("地铁") || category.contains("公交") || category.contains("交通")
                || category.contains("停车") || category.contains("加油")
                || category.contains("充电")) {
            return "交通";
        }
        if (category.contains("景点") || category.contains("公园") || category.contains("旅游")
                || category.contains("博物馆")) {
            return "景点";
        }
        if (category.contains("购物") || category.contains("商场") || category.contains("超市")
                || category.contains("商店")) {
            return "购物";
        }
        if (category.contains("商业") || category.contains("写字楼") || category.contains("公司")) {
            return "商业";
        }
        return category;
    }

    public List<Map<String, Object>> bufferStats(double lon, double lat, int radius) {
        return poiService.bufferStats(lon, lat, clampRadius(radius));
    }

    public List<Map<String, Object>> districtStats(String nameOrCode) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT adcode, name, poi_cnt
                    FROM mv_district_stats
                    WHERE adcode = ? OR name = ?
                    ORDER BY poi_cnt DESC
                    LIMIT 5
                    """, nameOrCode, nameOrCode);
            if (!rows.isEmpty()) {
                return rows;
            }
        } catch (DataAccessException ignored) {
            // 物化视图缺失时回退实时统计
        }
        return jdbc.queryForList("""
                SELECT d.adcode, d.name, count(p.id) AS poi_cnt
                FROM district d
                LEFT JOIN poi p ON ST_Contains(d.geom, p.geom)
                WHERE d.adcode = ? OR d.name = ?
                GROUP BY d.adcode, d.name
                ORDER BY poi_cnt DESC
                LIMIT 5
                """, nameOrCode, nameOrCode);
    }

    public List<Map<String, Object>> listDistricts() {
        return jdbc.queryForList(
                "SELECT adcode, name FROM district ORDER BY adcode");
    }

    public List<Map<String, Object>> districtRanking(int limit) {
        try {
            return jdbc.queryForList("""
                    SELECT adcode, name, poi_cnt
                    FROM mv_district_stats
                    ORDER BY poi_cnt DESC
                    LIMIT ?
                    """, limit);
        } catch (DataAccessException e) {
            return jdbc.queryForList("""
                    SELECT d.adcode, d.name, count(p.id) AS poi_cnt
                    FROM district d
                    LEFT JOIN poi p ON ST_Contains(d.geom, p.geom)
                    GROUP BY d.adcode, d.name
                    ORDER BY poi_cnt DESC
                    LIMIT ?
                    """, limit);
        }
    }

    public Map<String, Object> defaultCenter() {
        try {
            return jdbc.queryForMap("""
                    SELECT ST_X(ST_Centroid(ST_Union(geom))) AS lon,
                           ST_Y(ST_Centroid(ST_Union(geom))) AS lat
                    FROM district
                    """);
        } catch (DataAccessException e) {
            return Map.of();
        }
    }

    public Map<String, Object> districtCenter(String nameOrCode) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT adcode, name,
                       ST_X(ST_Centroid(geom)) AS lon,
                       ST_Y(ST_Centroid(geom)) AS lat
                FROM district
                WHERE adcode = ? OR name = ?
                LIMIT 1
                """, nameOrCode, nameOrCode);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private int clampRadius(int radius) {
        return Math.min(Math.max(radius, 1), 50000);
    }

    private int clampLimit(int limit, int max) {
        return Math.min(Math.max(limit, 1), max);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
