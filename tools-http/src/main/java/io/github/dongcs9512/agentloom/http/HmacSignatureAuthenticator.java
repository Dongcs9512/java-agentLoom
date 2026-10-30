package io.github.dongcs9512.agentloom.http;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

public final class HmacSignatureAuthenticator implements Authenticator {

    private final String accessKey;
    private final String secret;

    public HmacSignatureAuthenticator(String accessKey, String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("HMAC 密钥不能为空");
        }
        this.accessKey = accessKey;
        this.secret = secret;
    }

    @Override
    public Map<String, String> applyHeaders(Map<String, Object> context) {
        String method = (String) context.getOrDefault("method", "");
        String url = (String) context.getOrDefault("url", "");
        String body = (String) context.getOrDefault("body", "");
        String path = pathOf(url);
        long timestamp = System.currentTimeMillis() / 1000;
        String stringToSign = method + "\n" + path + "\n" + timestamp + "\n" + body;
        return Map.of(
                "X-Timestamp", String.valueOf(timestamp),
                "X-Access-Key", accessKey,
                "X-Signature", hmacSha256(stringToSign));
    }

    private String pathOf(String url) {
        try {
            return URI.create(url).getPath();
        } catch (Exception e) {
            return url;
        }
    }

    private String hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败: " + e.getMessage(), e);
        }
    }
}
