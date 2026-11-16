package com.example.poi.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    @Test
    void generateAndParseToken() {
        JwtService service = new JwtService(
                "test-secret-key-test-secret-key-test-secret-key", 30);

        String token = service.generate("alice", List.of("ROLE_ADMIN"));
        Claims claims = service.parse(token).getPayload();

        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.get("authorities", List.class)).contains("ROLE_ADMIN");
    }
}
