package com.example.poi.service;

import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

@Service
public class ImportService {

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public ImportService(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public long createTask(String filename) {
        Long id = jdbc.queryForObject(
                "INSERT INTO import_task(filename, status) VALUES (?, 'PENDING') RETURNING id",
                Long.class, filename);
        return id == null ? -1 : id;
    }

    public Map<String, Object> getTask(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, filename, status, total_cnt, success_cnt, failed_cnt, " +
                "error_msg, created_at, finished_at FROM import_task WHERE id = ?", id);
        return rows.isEmpty() ? Map.of("id", id, "status", "NOT_FOUND") : rows.get(0);
    }

    @Async("importExecutor")
    public void process(long taskId, Path csvPath) {
        processNow(taskId, csvPath, true);
    }

    public boolean processNow(long taskId, Path csvPath, boolean deleteOnFailure) {
        jdbc.update("UPDATE import_task SET status = 'RUNNING' WHERE id = ?", taskId);
        int total = 0;
        int success = 0;
        boolean ok = false;
        try {
            long lines = Files.lines(csvPath).count();
            total = (int) Math.max(lines - 1, 0);

            jdbc.update("TRUNCATE poi_staging");

            try (Connection conn = dataSource.getConnection()) {
                CopyManager copyManager = new CopyManager(conn.unwrap(BaseConnection.class));
                try (Reader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
                    copyManager.copyIn(
                            "COPY poi_staging(name, category, lng, lat, source_id) " +
                            "FROM STDIN WITH (FORMAT csv, HEADER true)",
                            reader);
                }
            }

            success = jdbc.update("""
                    INSERT INTO poi (name, category, lng, lat, geom, geom_4549, source_id)
                    SELECT name, category, lng, lat,
                           ST_SetSRID(ST_MakePoint(lng, lat), 4326),
                           ST_Transform(ST_SetSRID(ST_MakePoint(lng, lat), 4326), 4549),
                           source_id
                    FROM poi_staging
                    WHERE name IS NOT NULL
                      AND category IS NOT NULL
                      AND lng BETWEEN -180 AND 180
                      AND lat BETWEEN -90 AND 90
                    ON CONFLICT (source_id) DO NOTHING
                    """);

            jdbc.update("""
                    UPDATE import_task
                    SET status = 'SUCCESS', total_cnt = ?, success_cnt = ?,
                        failed_cnt = ?, finished_at = now()
                    WHERE id = ?
                    """, total, success, Math.max(total - success, 0), taskId);
            ok = true;
        } catch (Exception e) {
            jdbc.update("""
                    UPDATE import_task
                    SET status = 'FAILED', total_cnt = ?, success_cnt = ?,
                        failed_cnt = ?, error_msg = ?, finished_at = now()
                    WHERE id = ?
                    """, total, success, Math.max(total - success, 0),
                    String.valueOf(e.getMessage()), taskId);
        } finally {
            if (ok || deleteOnFailure) {
                try {
                    Files.deleteIfExists(csvPath);
                } catch (IOException ignored) {
                }
            }
        }
        return ok;
    }
}
