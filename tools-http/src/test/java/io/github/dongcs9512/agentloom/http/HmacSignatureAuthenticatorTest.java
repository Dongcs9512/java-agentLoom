package io.github.dongcs9512.agentloom.http;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HmacSignatureAuthenticatorTest {

    @Test
    void hmacProducesTimestampAccessKeySignature() throws Exception {
        HmacSignatureAuthenticator auth = new HmacSignatureAuthenticator("ak-1", "secret");
        Map<String, String> headers = auth.applyHeaders(Map.of("method", "POST", "url", "http://x/api/q", "body", "{}"));

        assertEquals("ak-1", headers.get("X-Access-Key"));
        String ts = headers.get("X-Timestamp");
        assertNotNull(ts);

        String toSign = "POST\n/api/q\n" + ts + "\n{}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, headers.get("X-Signature"));
    }

    @Test
    void hmacRejectsEmptySecret() {
        assertThrows(IllegalArgumentException.class, () -> new HmacSignatureAuthenticator("ak", ""));
    }
}
