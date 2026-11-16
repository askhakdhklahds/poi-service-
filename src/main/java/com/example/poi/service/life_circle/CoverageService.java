package com.example.poi.service.life_circle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CoverageService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CoverageService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> categories() {
        return jdbc.queryForList("""
                SELECT code, name_zh, poi_category, threshold_seconds, euclid_radius_m
                FROM facility_standard
                WHERE enabled
                ORDER BY code
                """);
    }

    @Cacheable(value = "lifeCircleCoverage",
            key = "#adcode + ':' + #category + ':' + #thresholdSeconds")
    public Map<String, Object> coverage(String adcode, String category,
                                        int thresholdSeconds) {
        List<Map<String, Object>> standards = jdbc.queryForList("""
                SELECT code, name_zh, poi_category
                FROM facility_standard
                WHERE code = ? AND enabled
                """, category);
        if (standards.isEmpty()) {
            throw new IllegalArgumentException("unknown category: " + category);
        }
        Map<String, Object> standard = standards.get(0);
        String poiCategory = String.valueOf(standard.get("poi_category"));
        String categoryName = String.valueOf(standard.get("name_zh"));

        List<Map<String, Object>> districts = jdbc.queryForList(
                "SELECT name FROM district WHERE adcode = ?", adcode);
        if (districts.isEmpty()) {
            throw new IllegalArgumentException("unknown district: " + adcode);
        }
        String districtName = String.valueOf(districts.get(0).get("name"));

        // 步行按 1.2 m/s 估算：5/10/15 分钟 -> 360/720/1080 米
        int radius = Math.max(1, Math.round(thresholdSeconds * 1.2f));

        String sql = """
                WITH covered AS (
                    SELECT g.id,
                           min(ST_Distance(p.geom_4549, g.geom_4549)) AS distance_m
                    FROM population_grid g
                    JOIN poi p ON ST_DWithin(p.geom_4549, g.geom_4549, ?)
                    WHERE g.district_adcode = ?
                      AND p.category = ?
                    GROUP BY g.id
                ),
                cells AS (
                    SELECT g.id,
                           g.pop,
                           g.geom,
                           g.cell_size_m,
                           c.distance_m,
                           (c.id IS NOT NULL) AS covered
                    FROM population_grid g
                    LEFT JOIN covered c ON c.id = g.id
                    WHERE g.district_adcode = ?
                      AND g.pop > 0
                )
                SELECT count(*) AS cell_count,
                       count(*) FILTER (WHERE covered) AS covered_cells,
                       coalesce(sum(pop), 0) AS pop_total,
                       coalesce(sum(pop) FILTER (WHERE covered), 0) AS pop_covered,
                       max(cell_size_m) AS cell_size_m,
                       (SELECT jsonb_build_object(
                           'type', 'FeatureCollection',
                           'features', coalesce(jsonb_agg(jsonb_build_object(
                               'type', 'Feature',
                               'geometry', ST_AsGeoJSON(
                                   ST_Expand(cells.geom,
                                             cells.cell_size_m / 2.0 / 111320.0))::jsonb,
                               'properties', jsonb_build_object(
                                   'gridId', cells.id,
                                   'pop', round(cells.pop),
                                   'covered', cells.covered,
                                   'nearestM', round(cells.distance_m)
                               )
                           )), '[]'::jsonb)
                       )::text
                        FROM cells) AS geojson
                FROM cells
                """;

        Map<String, Object> row = jdbc.queryForMap(
                sql, radius, adcode, poiCategory, adcode);

        double popTotal = number(row.get("pop_total"));
        double popCovered = number(row.get("pop_covered"));
        JsonNode geojson;
        try {
            geojson = mapper.readTree(String.valueOf(row.get("geojson")));
        } catch (Exception e) {
            geojson = mapper.createObjectNode()
                    .put("type", "FeatureCollection")
                    .set("features", mapper.createArrayNode());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("adcode", adcode);
        out.put("districtName", districtName);
        out.put("category", category);
        out.put("categoryName", categoryName);
        out.put("thresholdSeconds", thresholdSeconds);
        out.put("radiusM", radius);
        out.put("cellCount", ((Number) row.get("cell_count")).longValue());
        out.put("coveredCells", ((Number) row.get("covered_cells")).longValue());
        out.put("cellSizeM", row.get("cell_size_m") instanceof Number number
                ? number.intValue() : null);
        out.put("popTotal", Math.round(popTotal));
        out.put("popCovered", Math.round(popCovered));
        out.put("coverageRatio", popTotal == 0 ? 0.0 : popCovered / popTotal);
        out.put("gapPopulation", Math.round(popTotal - popCovered));
        out.put("geojson", geojson);
        return out;
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }
}
