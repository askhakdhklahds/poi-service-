package com.example.poi.config;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public Gauge tileCacheHitRate(Cache<String, byte[]> tileCache,
                                  MeterRegistry registry) {
        return Gauge.builder("poi.tile.cache.hit.rate", tileCache,
                        c -> c.stats().hitRate())
                .description("Tile cache hit rate")
                .register(registry);
    }

    @Bean
    public Gauge tileCacheHits(Cache<String, byte[]> tileCache,
                               MeterRegistry registry) {
        return Gauge.builder("poi.tile.cache.hits", tileCache,
                        c -> c.stats().hitCount())
                .description("Tile cache hit count")
                .register(registry);
    }

    @Bean
    public Gauge tileCacheMisses(Cache<String, byte[]> tileCache,
                                 MeterRegistry registry) {
        return Gauge.builder("poi.tile.cache.misses", tileCache,
                        c -> c.stats().missCount())
                .description("Tile cache miss count")
                .register(registry);
    }

    @Bean
    public Gauge tileCacheSize(Cache<String, byte[]> tileCache,
                               MeterRegistry registry) {
        return Gauge.builder("poi.tile.cache.size", tileCache,
                        c -> c.estimatedSize())
                .description("Estimated number of cached tiles")
                .register(registry);
    }
}
