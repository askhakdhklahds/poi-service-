package com.example.poi.service;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DistrictService {

    private final JdbcTemplate jdbc;

    public DistrictService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Cacheable(value = "districtStats", key = "#adcode")
    public Map<String, Object> stats(String adcode) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT adcode, name, poi_cnt FROM mv_district_stats WHERE adcode = ?",
                    adcode);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
        } catch (DataAccessException ignored) {
            // 物化视图尚未创建时回退到实时计算
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.adcode, d.name, count(p.id) AS poi_cnt
                FROM district d
                LEFT JOIN poi p ON ST_Contains(d.geom, p.geom)
                WHERE d.adcode = ?
                GROUP BY d.adcode, d.name
                """, adcode);
        return rows.isEmpty()
                ? Map.of("adcode", adcode, "name", "", "poi_cnt", 0)
                : rows.get(0);
    }

    @Cacheable(value = "districtList", key = "'all'")
    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
                "SELECT adcode, name FROM district ORDER BY adcode");
    }
}
