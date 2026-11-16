package com.example.poi.controller;

import com.example.poi.service.PoiService;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TileController {

    private final PoiService service;
    private final Cache<String, byte[]> tileCache;

    public TileController(PoiService service, Cache<String, byte[]> tileCache) {
        this.service = service;
        this.tileCache = tileCache;
    }

    @GetMapping(value = "/api/tiles/{z}/{x}/{y}.mvt",
            produces = "application/vnd.mapbox-vector-tile")
    public ResponseEntity<byte[]> tile(@PathVariable int z,
                                       @PathVariable int x,
                                       @PathVariable int y,
                                       @RequestParam(required = false) String category) {
        String key = z + "/" + x + "/" + y + "/" + (category == null ? "" : category);
        byte[] cached = tileCache.getIfPresent(key);
        boolean hit = cached != null;
        byte[] body = hit ? cached : service.tile(z, x, y, category);
        if (!hit) {
            tileCache.put(key, body);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.mapbox-vector-tile"))
                .header("Cache-Control", "public, max-age=60")
                .header("X-Tile-Cached", hit ? "HIT" : "MISS")
                .body(body);
    }
}
