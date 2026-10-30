package io.github.dongcs9512.agentloom.session.redis;

import io.github.dongcs9512.agentloom.core.session.AnswerCache;
import io.github.dongcs9512.agentloom.session.CacheKey;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 热点问题答案的 Redis 缓存，带 TTL。
 */
public final class RedisAnswerCache implements AnswerCache, AutoCloseable {

    private final JedisPool pool;
    private final boolean ownsPool;
    private final String keyPrefix;
    private final int ttlSeconds;

    public RedisAnswerCache(String host, int port, String password, String keyPrefix, int ttlSeconds) {
        this(new JedisPool(new HostAndPort(host, port),
                DefaultJedisClientConfig.builder().password(password).build()), keyPrefix, ttlSeconds, true);
    }

    public RedisAnswerCache(JedisPool pool, String keyPrefix, int ttlSeconds) {
        this(pool, keyPrefix, ttlSeconds, false);
    }

    private RedisAnswerCache(JedisPool pool, String keyPrefix, int ttlSeconds, boolean ownsPool) {
        this.pool = pool;
        this.ownsPool = ownsPool;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "agentloom:answer:" : keyPrefix;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public Optional<String> get(String agentId, String question) {
        String normalized = io.github.dongcs9512.agentloom.core.session.QuestionNormalizer.normalize(question);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        try (Jedis jedis = pool.getResource()) {
            return Optional.ofNullable(jedis.get(key(agentId, question)));
        }
    }

    @Override
    public void put(String agentId, String question, String answer) {
        String normalized = io.github.dongcs9512.agentloom.core.session.QuestionNormalizer.normalize(question);
        if (normalized.isEmpty() || answer == null || answer.isBlank()) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            String key = key(agentId, question);
            jedis.set(key, answer);
            if (ttlSeconds > 0) {
                jedis.expire(key, ttlSeconds);
            }
        }
    }

    @Override
    public void close() {
        if (ownsPool) {
            pool.close();
        }
    }

    private String key(String agentId, String question) {
        return keyPrefix + CacheKey.of(agentId, question);
    }

    static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
