package io.github.dongcs9512.agentloom.http;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtAuthenticatorTest {

    @Test
    void jwtHasBearerHeaderWith3Segments() {
        JwtAuthenticator auth = new JwtAuthenticator("HS256", "mysecret", Map.of("iss", "ai-agent"), 60);
        Map<String, String> headers = auth.applyHeaders(Map.of());
        String jwt = headers.get("Authorization").substring("Bearer ".length());

        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);

        String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
        assertTrue(headerJson.contains("HS256"));
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
        assertTrue(payloadJson.contains("ai-agent"));
    }

    @Test
    void jwtRejectsEmptySecret() {
        assertThrows(IllegalArgumentException.class, () -> new JwtAuthenticator("HS256", "", Map.of(), 60));
    }
}
