package io.github.dongcs9512.agentloom.http;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StaticHeaderAuthenticatorTest {

    @Test
    void returnsConfiguredHeaders() {
        StaticHeaderAuthenticator auth = new StaticHeaderAuthenticator(Map.of("Authorization", "Bearer x"));
        Map<String, String> headers = auth.applyHeaders(Map.of());
        assertEquals("Bearer x", headers.get("Authorization"));
    }

    @Test
    void rejectsEmptyHeaders() {
        assertThrows(IllegalArgumentException.class, () -> new StaticHeaderAuthenticator(Map.of()));
    }
}
