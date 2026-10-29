package com.aia.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JwtAuthenticator implements Authenticator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String algorithm;
    private final String secret;
    private final Map<String, Object> claims;
    private final int expInSeconds;

    public JwtAuthenticator(String algorithm, String secret, Map<String, Object> claims, int expInSeconds) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("JWT 密钥不能为空");
        }
        if (!"HS256".equals(algorithm) && !"RS256".equals(algorithm)) {
            throw new IllegalArgumentException("不支持的 JWT 算法: " + algorithm);
        }
        this.algorithm = algorithm;
        this.secret = secret;
        this.claims = claims == null ? Map.of() : claims;
        this.expInSeconds = expInSeconds;
    }

    @Override
    public Map<String, String> applyHeaders(Map<String, Object> context) {
        return Map.of("Authorization", "Bearer " + sign());
    }

    private String sign() {
        try {
            String header = base64Url(MAPPER.writeValueAsString(Map.of("alg", algorithm, "typ", "JWT")));
            Map<String, Object> payload = new LinkedHashMap<>(claims);
            long now = System.currentTimeMillis() / 1000;
            payload.put("iat", now);
            payload.put("exp", now + expInSeconds);
            String payloadEncoded = base64Url(MAPPER.writeValueAsString(payload));
            String signingInput = header + "." + payloadEncoded;
            byte[] signature = "HS256".equals(algorithm) ? signHmac(signingInput) : signRsa(signingInput);
            return signingInput + "." + base64Url(signature);
        } catch (Exception e) {
            throw new IllegalStateException("生成 JWT 失败: " + e.getMessage(), e);
        }
    }

    private byte[] signHmac(String signingInput) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] signRsa(String signingInput) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(parsePrivateKey());
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return sig.sign();
    }

    private PrivateKey parsePrivateKey() throws Exception {
        String pem = secret.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String base64Url(String s) {
        return base64Url(s.getBytes(StandardCharsets.UTF_8));
    }
}
