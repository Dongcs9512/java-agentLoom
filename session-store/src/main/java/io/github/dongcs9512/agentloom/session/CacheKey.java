package io.github.dongcs9512.agentloom.session;

import io.github.dongcs9512.agentloom.core.session.QuestionNormalizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 缓存 key：agent + 归一化问题的 SHA-256，避免中文与标点直接进 key。
 */
public final class CacheKey {

    private CacheKey() {
    }

    public static String of(String agentId, String question) {
        return (agentId == null ? "" : agentId) + ":" + sha256Hex(QuestionNormalizer.normalize(question));
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
