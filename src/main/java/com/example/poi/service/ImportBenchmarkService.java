package com.example.poi.service;

import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class ImportBenchmarkService {

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public ImportBenchmarkService(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public Map<String, Object> run(int rows, String mode) throws Exception {
        String prefix = "bench_" + System.currentTimeMillis() + "_";
        long start = System.nanoTime();
        int inserted;

        if ("copy".equalsIgnoreCase(mode)) {
            Path csv = Files.createTempFile("bench-", ".csv");
            try {
                writeCsv(csv, rows, prefix);
                jdbc.update("TRUNCATE poi_staging");
                try (Connection conn = dataSource.getConnection()) {
                    CopyManager cm = new CopyManager(conn.unwrap(BaseConnection.class));
                    try (Reader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
                        cm.copyIn("COPY poi_staging(name, category, lng, lat, source_id) " +
                                "FROM STDIN WITH (FORMAT csv, HEADER true)", reader);
                    }
                }
                inserted = jdbc.update("""
                        INSERT INTO poi (name, category, lng, lat, geom, geom_4549, source_id)
                        SELECT name, category, lng, lat,
                               ST_SetSRID(ST_MakePoint(lng, lat), 4326),
                               ST_Transform(ST_SetSRID(ST_MakePoint(lng, lat), 4326), 4549),
                               source_id
                        FROM poi_staging
                        ON CONFLICT (source_id) DO NOTHING
                        """);
            } finally {
                Files.deleteIfExists(csv);
            }
        } else if ("batch".equalsIgnoreCase(mode)) {
            List<Object[]> batch = new ArrayList<>(rows);
            Random random = new Random();
            for (int i = 0; i < rows; i++) {
                double lng = 121.0 + random.nextDouble() * 0.5;
                double lat = 31.0 + random.nextDouble() * 0.3;
                batch.add(new Object[]{
                        "bench_" + i, "餐饮", lng, lat,
                        lng, lat, lng, lat,
                        prefix + i
                });
            }
            int[] result = jdbc.batchUpdate("""
                    INSERT INTO poi (name, category, lng, lat, geom, geom_4549, source_id)
                    VALUES (?, ?, ?, ?,
                            ST_SetSRID(ST_MakePoint(?, ?), 4326),
                            ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 4549),
                            ?)
                    ON CONFLICT (source_id) DO NOTHING
                    """, batch);
            inserted = 0;
            for (int r : result) {
                if (r > 0) {
                    inserted += r;
                }
            }
        } else if ("row".equalsIgnoreCase(mode)) {
            String sql = """
                    INSERT INTO poi (name, category, lng, lat, geom, geom_4549, source_id)
                    VALUES (?, ?, ?, ?,
                            ST_SetSRID(ST_MakePoint(?, ?), 4326),
                            ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 4549),
                            ?)
                    ON CONFLICT (source_id) DO NOTHING
                    """;
            Random random = new Random();
            inserted = 0;
            for (int i = 0; i < rows; i++) {
                double lng = 121.0 + random.nextDouble() * 0.5;
                double lat = 31.0 + random.nextDouble() * 0.3;
                inserted += jdbc.update(sql,
                        "bench_" + i, "餐饮", lng, lat,
                        lng, lat, lng, lat,
                        prefix + i);
            }
        } else {
            throw new IllegalArgumentException("mode must be copy, batch or row");
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        jdbc.update("DELETE FROM poi WHERE source_id LIKE 'bench_%'");

        return Map.of(
                "mode", mode,
                "rows", rows,
                "inserted", inserted,
                "elapsedMs", elapsedMs,
                "rowsPerSecond", elapsedMs == 0 ? rows : (rows * 1000L / elapsedMs)
        );
    }

    private void writeCsv(Path csv, int rows, String prefix) throws Exception {
        Random random = new Random();
        try (Writer writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("name,category,lng,lat,source_id\n");
            for (int i = 0; i < rows; i++) {
                double lng = 121.0 + random.nextDouble() * 0.5;
                double lat = 31.0 + random.nextDouble() * 0.3;
                writer.write("bench_" + i + ",餐饮," + lng + "," + lat + ","
                        + prefix + i + "\n");
            }
        }
    }
}
