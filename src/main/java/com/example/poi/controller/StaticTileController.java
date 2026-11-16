package com.example.poi.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@RestController
public class StaticTileController {

    @Value("${tiles.dir:D:/jue/gis/poi-service/tiles}")
    private String tilesDir;

    @Value("${tiles.gcj.dir:D:/jue/gis/poi-service/tiles-gcj}")
    private String tilesGcjDir;

    @GetMapping(value = "/tiles/{z}/{x}/{y}.pbf",
            produces = "application/vnd.mapbox-vector-tile")
    public ResponseEntity<byte[]> tile(@PathVariable int z,
                                       @PathVariable int x,
                                       @PathVariable int y) throws IOException {
        return serve(tilesDir, z, x, y);
    }

    @GetMapping(value = "/tiles-gcj/{z}/{x}/{y}.pbf",
            produces = "application/vnd.mapbox-vector-tile")
    public ResponseEntity<byte[]> gcjTile(@PathVariable int z,
                                          @PathVariable int x,
                                          @PathVariable int y) throws IOException {
        return serve(tilesGcjDir, z, x, y);
    }

    private ResponseEntity<byte[]> serve(String baseDir, int z, int x, int y)
            throws IOException {
        if (z < 0 || x < 0 || y < 0) {
            return ResponseEntity.badRequest().build();
        }
        Path file = Path.of(baseDir, String.valueOf(z),
                String.valueOf(x), y + ".pbf");
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.mapbox-vector-tile"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(Files.readAllBytes(file));
    }
}
