package com.example.poi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PoiApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgis/postgis:17-3.6");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rate-limit.enabled", () -> "false");
        registry.add("agent.llm.enabled", () -> "false");
        registry.add("spring.cache.type", () -> "none");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void countWithinAndAuth() {
        jdbc.update("""
                INSERT INTO poi (name, category, sub_category, lng, lat, geom, geom_4549, source_id)
                VALUES ('测试医院', '医院', '医院', 121.47, 31.23,
                        ST_SetSRID(ST_MakePoint(121.47, 31.23), 4326),
                        ST_Transform(ST_SetSRID(ST_MakePoint(121.47, 31.23), 4326), 4549),
                        'test_1')
                ON CONFLICT (source_id) DO NOTHING
                """);

        Map<?, ?> count = rest.getForObject("/api/poi/count", Map.class);
        Map<?, ?> countData = (Map<?, ?>) count.get("data");
        assertThat(((Number) countData.get("count")).intValue()).isEqualTo(1);

        Map<?, ?> within = rest.getForObject(
                "/api/poi/within?lon=121.47&lat=31.23&radius=1000", Map.class);
        assertThat((List<?>) within.get("data")).isNotEmpty();

        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> login = rest.postForEntity("/api/auth/login",
                new HttpEntity<>("{\"username\":\"admin\",\"password\":\"admin123\"}", json),
                Map.class);
        assertThat(login.getStatusCode().is2xxSuccessful()).isTrue();
        Map<?, ?> loginData = (Map<?, ?>) login.getBody().get("data");
        String token = String.valueOf(loginData.get("token"));

        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(token);
        ResponseEntity<Map> admin = rest.exchange("/api/admin/tile-cache-stats",
                HttpMethod.GET, new HttpEntity<>(auth), Map.class);
        assertThat(admin.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<Map> anonymous = rest.getForEntity(
                "/api/admin/tile-cache-stats", Map.class);
        assertThat(anonymous.getStatusCode().value()).isIn(401, 403);
    }
}
