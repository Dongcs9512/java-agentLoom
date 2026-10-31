package io.github.dongcs9512.agentloom.session.redis;

import io.github.dongcs9512.agentloom.core.session.Session;
import io.github.dongcs9512.agentloom.core.session.SessionStore;
import io.github.dongcs9512.agentloom.session.SessionSerializer;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 会话热存储：每个会话是一个 HASH，带 TTL 自动过期，避免会话无限堆积。
 */
public final class RedisSessionStore implements SessionStore, AutoCloseable {

    private static final byte[] F_AGENT_ID = bytes("agentId");
    private static final byte[] F_SUMMARY = bytes("summary");
    private static final byte[] F_SLOTS = bytes("slots");
    private static final byte[] F_MESSAGES = bytes("messages");
    private static final byte[] F_UPDATED_AT = bytes("updatedAt");
    private static final byte[] F_OPERATOR = bytes("operator");

    private final JedisPool pool;
    private final boolean ownsPool;
    private final String keyPrefix;
    private final int ttlSeconds;

    public RedisSessionStore(String host, int port, String password, String keyPrefix, int ttlSeconds) {
        this(new JedisPool(new HostAndPort(host, port),
                DefaultJedisClientConfig.builder().password(password).build()), keyPrefix, ttlSeconds, true);
    }

    public RedisSessionStore(JedisPool pool, String keyPrefix, int ttlSeconds) {
        this(pool, keyPrefix, ttlSeconds, false);
    }

    private RedisSessionStore(JedisPool pool, String keyPrefix, int ttlSeconds, boolean ownsPool) {
        this.pool = pool;
        this.ownsPool = ownsPool;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "agentloom:session:" : keyPrefix;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void save(Session session) {
        Map<byte[], byte[]> hash = new LinkedHashMap<>();
        hash.put(F_AGENT_ID, bytes(session.agentId() == null ? "" : session.agentId()));
        hash.put(F_SUMMARY, bytes(session.summary() == null ? "" : session.summary()));
        hash.put(F_SLOTS, bytes(SessionSerializer.slotsToJson(session.slots())));
        hash.put(F_MESSAGES, bytes(SessionSerializer.messagesToJson(session.messages())));
        hash.put(F_UPDATED_AT, bytes(String.valueOf(session.updatedAt())));
        hash.put(F_OPERATOR, bytes(session.operator() == null ? "" : session.operator()));

        byte[] key = key(session.id());
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(key, hash);
            if (ttlSeconds > 0) {
                jedis.expire(key, ttlSeconds);
            }
        }
    }

    @Override
    public Optional<Session> load(String sessionId) {
        Map<byte[], byte[]> data;
        try (Jedis jedis = pool.getResource()) {
            data = jedis.hgetAll(key(sessionId));
        }
        if (data == null || data.isEmpty()) {
            return Optional.empty();
        }
        Map<String, byte[]> byName = new HashMap<>();
        data.forEach((k, v) -> byName.put(new String(k, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT), v));

        String agentId = str(byName.get("agentid"));
        String summary = str(byName.get("summary"));
        String updatedAt = str(byName.get("updatedat"));

        String operator = str(byName.get("operator"));

        return Optional.of(new Session(
                sessionId,
                agentId,
                SessionSerializer.messagesFromJson(str(byName.get("messages"))),
                summary == null || summary.isBlank() ? null : summary,
                SessionSerializer.slotsFromJson(str(byName.get("slots"))),
                updatedAt == null || updatedAt.isBlank() ? 0 : Long.parseLong(updatedAt),
                operator == null || operator.isBlank() ? null : operator));
    }

    @Override
    public void delete(String sessionId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(key(sessionId));
        }
    }

    @Override
    public void close() {
        if (ownsPool) {
            pool.close();
        }
    }

    private byte[] key(String sessionId) {
        return bytes(keyPrefix + sessionId);
    }

    private static String str(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
