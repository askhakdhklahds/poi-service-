package com.example.poi.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class PoiService {

    private final JdbcTemplate jdbc;

    public PoiService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> within(double lon, double lat, int radius,
                                            String category, int limit) {
        StringBuilder sql = new StringBuilder("""
                WITH p AS (
                    SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 4549) AS pt
                )
                SELECT id, name, category, sub_category,
                       ROUND(ST_Distance(geom_4549, p.pt)) AS distance_m,
                       ST_X(geom) AS lon, ST_Y(geom) AS lat,
                       ST_AsGeoJSON(geom) AS geojson
                FROM poi, p
                WHERE ST_DWithin(geom_4549, p.pt, ?)
                """);
        List<Object> args = new ArrayList<>(List.of(lon, lat, radius));
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ? ");
            args.add(category);
        }
        sql.append(" ORDER BY distance_m LIMIT ? ");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> nearest(double lon, double lat, int limit) {
        return nearest(lon, lat, limit, null);
    }

    public List<Map<String, Object>> nearest(double lon, double lat, int limit,
                                             String category) {
        StringBuilder sql = new StringBuilder("""
                WITH p AS (
                    SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 4549) AS pt
                )
                SELECT id, name, category, sub_category,
                       ROUND(ST_Distance(geom_4549, p.pt)) AS distance_m,
                       ST_X(geom) AS lon, ST_Y(geom) AS lat,
                       ST_AsGeoJSON(geom) AS geojson
                FROM poi, p
                """);
        List<Object> args = new ArrayList<>(List.of(lon, lat));
        if (category != null && !category.isBlank()) {
            sql.append(" WHERE category = ? ");
            args.add(category);
        }
        sql.append(" ORDER BY geom_4549 <-> p.pt LIMIT ? ");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> searchPlaces(String keyword, int limit) {
        String like = "%" + keyword + "%";
        String sql = """
                SELECT name, category, sub_category, lon, lat FROM (
                    SELECT p.name,
                           p.category,
                           p.sub_category,
                           ST_X(p.geom) AS lon,
                           ST_Y(p.geom) AS lat,
                           CASE WHEN p.name = ? THEN 0 ELSE 1 END AS exact_rank,
                           char_length(p.name) AS name_len,
                           0 AS kind
                    FROM poi p
                    WHERE p.name ILIKE ?
                    UNION ALL
                    SELECT d.name,
                           '行政区' AS category,
                           NULL AS sub_category,
                           ST_X(ST_Centroid(d.geom)) AS lon,
                           ST_Y(ST_Centroid(d.geom)) AS lat,
                           CASE WHEN d.name = ? THEN 0 ELSE 1 END AS exact_rank,
                           char_length(d.name) AS name_len,
                           1 AS kind
                    FROM district d
                    WHERE d.name ILIKE ?
                ) t
                ORDER BY exact_rank, kind, name_len, name
                LIMIT ?
                """;
        return jdbc.queryForList(sql, keyword, like, keyword, like, limit);
    }

    public List<Map<String, Object>> bufferStats(double lon, double lat, int radius) {
        String sql = """
                WITH p AS (
                    SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 4549) AS pt
                )
                SELECT category, count(*) AS cnt
                FROM poi, p
                WHERE ST_DWithin(geom_4549, p.pt, ?)
                GROUP BY category
                ORDER BY cnt DESC
                """;
        return jdbc.queryForList(sql, lon, lat, radius);
    }

    public byte[] tile(int z, int x, int y, String category) {
        StringBuilder sql = new StringBuilder("""
                WITH bounds AS (
                    SELECT ST_TileEnvelope(?, ?, ?) AS geom
                )
                SELECT ST_AsMVT(q, 'poi', 4096, 'geom') AS mvt
                FROM (
                    SELECT p.id, p.name, p.category,
                           ST_AsMVTGeom(ST_Transform(p.geom, 3857), b.geom, 4096, 256, true) AS geom
                    FROM poi p, bounds b
                    WHERE ST_Intersects(p.geom, ST_Transform(b.geom, 4326))
                """);
        List<Object> args = new ArrayList<>(List.of(z, x, y));
        if (category != null && !category.isBlank()) {
            sql.append(" AND p.category = ? ");
            args.add(category);
        }
        sql.append(" LIMIT 20000 ) q ");
        byte[] result = jdbc.queryForObject(sql.toString(), byte[].class, args.toArray());
        return result == null ? new byte[0] : result;
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM poi", Long.class);
        return n == null ? 0 : n;
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id, p.name, p.category, p.sub_category, p.lng, p.lat,
                       p.source_id, p.created_at,
                       d.adcode AS district_adcode,
                       d.name AS district_name
                FROM poi p
                LEFT JOIN district d ON ST_Contains(d.geom, p.geom)
                WHERE p.id = ?
                """, id);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public Map<String, Object> stats() {
        List<Map<String, Object>> categories = jdbc.queryForList("""
                SELECT category, count(*) AS cnt
                FROM poi
                GROUP BY category
                ORDER BY cnt DESC
                """);
        List<Map<String, Object>> subCategories = jdbc.queryForList("""
                SELECT category, sub_category, count(*) AS cnt
                FROM poi
                GROUP BY category, sub_category
                ORDER BY category, cnt DESC
                """);
        return Map.of(
                "total", count(),
                "categories", categories,
                "subCategories", subCategories);
    }

    public String geoJsonSample(int limit) {
        String sql = """
                SELECT jsonb_build_object(
                    'type', 'FeatureCollection',
                    'features', COALESCE(jsonb_agg(jsonb_build_object(
                        'type', 'Feature',
                        'geometry', ST_AsGeoJSON(geom)::jsonb,
                        'properties', jsonb_build_object(
                            'id', id, 'name', name, 'category', category
                        )
                    )), '[]'::jsonb)
                )::text
                FROM (
                    SELECT id, name, category, geom
                    FROM poi
                    LIMIT ?
                ) t
                """;
        return jdbc.queryForObject(sql, String.class, limit);
    }
}
