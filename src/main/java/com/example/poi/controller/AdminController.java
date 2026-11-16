package com.example.poi.controller;

import com.example.poi.service.GeoServerService;
import com.example.poi.service.ImportBenchmarkService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final JdbcTemplate jdbc;
    private final CacheManager cacheManager;
    private final Cache<String, byte[]> tileCache;
    private final GeoServerService geoServerService;
    private final ImportBenchmarkService benchmarkService;

    public AdminController(JdbcTemplate jdbc, CacheManager cacheManager,
                           Cache<String, byte[]> tileCache,
                           GeoServerService geoServerService,
                           ImportBenchmarkService benchmarkService) {
        this.jdbc = jdbc;
        this.cacheManager = cacheManager;
        this.tileCache = tileCache;
        this.geoServerService = geoServerService;
        this.benchmarkService = benchmarkService;
    }

    @PostMapping("/refresh-stats")
    public Map<String, Object> refreshStats() {
        String exists = jdbc.queryForObject(
                "SELECT to_regclass('public.mv_district_stats')::text", String.class);
        if (exists == null) {
            jdbc.execute("""
                    CREATE MATERIALIZED VIEW mv_district_stats AS
                    SELECT d.adcode, d.name, count(p.id) AS poi_cnt
                    FROM district d
                    LEFT JOIN poi p ON ST_Contains(d.geom, p.geom)
                    GROUP BY d.adcode, d.name
                    """);
            jdbc.execute("CREATE UNIQUE INDEX idx_mv_district_stats_adcode " +
                    "ON mv_district_stats (adcode)");
        } else {
            jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_district_stats");
        }
        var cache = cacheManager.getCache("districtStats");
        if (cache != null) {
            cache.clear();
        }
        return Map.of("code", 0, "message", "refreshed");
    }

    @GetMapping("/slow-queries")
    public Map<String, Object> slowQueries() {
        try {
            return Map.of("code", 0, "message", "ok", "data",
                    jdbc.queryForList("""
                            SELECT query, calls, total_exec_time, mean_exec_time, rows
                            FROM pg_stat_statements
                            ORDER BY total_exec_time DESC
                            LIMIT 10
                            """));
        } catch (Exception e) {
            return Map.of("code", 0,
                    "message", "pg_stat_statements not enabled: " + e.getMessage(),
                    "data", java.util.List.of());
        }
    }

    @GetMapping("/tile-cache-stats")
    public Map<String, Object> tileCacheStats() {
        CacheStats s = tileCache.stats();
        return Map.of(
                "hitCount", s.hitCount(),
                "missCount", s.missCount(),
                "hitRate", s.hitRate(),
                "size", tileCache.estimatedSize());
    }

    @PostMapping("/geoserver/publish")
    public Map<String, Object> publishGeoServer() {
        return geoServerService.publish();
    }

    @GetMapping("/import-benchmark")
    public Map<String, Object> importBenchmark(
            @RequestParam(defaultValue = "50000") int rows,
            @RequestParam(defaultValue = "copy") String mode) throws Exception {
        int safeRows = Math.min(Math.max(rows, 1000), 500000);
        return Map.of("code", 0, "message", "ok",
                "data", benchmarkService.run(safeRows, mode));
    }
}
