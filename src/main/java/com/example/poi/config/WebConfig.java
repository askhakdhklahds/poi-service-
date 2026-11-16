package com.example.poi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${tiles.dir:D:/jue/gis/poi-service/tiles}")
    private String tilesDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = tilesDir.endsWith("/") || tilesDir.endsWith("\\")
                ? "file:" + tilesDir
                : "file:" + tilesDir + "/";
        registry.addResourceHandler("/tiles/**")
                .addResourceLocations(location);
    }
}
