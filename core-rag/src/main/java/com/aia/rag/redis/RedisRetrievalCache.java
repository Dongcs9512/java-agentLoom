package com.aia.rag.redis;

import com.aia.core.session.QuestionNormalizer;
import com.aia.rag.RetrievalCache;
import com.aia.rag.SearchHit;
import com.aia.rag.VersionedRetrievalCache;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 检索结果缓存的 Redis 实现：多实例部署时共享命中，重启也不丢。
 *
 * <p>代价是每次命中多一跳网络（局域网约 0.3~1ms），所以生产上通常和内存缓存组成两级。
 */
public final class RedisRetrievalCache implements VersionedRetrievalCache, AutoCloseable {

    private static final String VERSION_SUFFIX = "__ver";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<SearchHit>> HIT_LIST = new TypeReference<>() {};

    private final JedisPool pool;
    private final boolean ownsPool;
    private final String keyPrefix;
    private final int ttlSeconds;

    public RedisRetrievalCache(String host, int port, String password, String keyPrefix, int ttlSeconds) {
        this(new JedisPool(new HostAndPort(host, port),
                DefaultJedisClientConfig.builder().password(password).build()), keyPrefix, ttlSeconds, true);
    }

    public RedisRetrievalCache(JedisPool pool, String keyPrefix, int ttlSeconds) {
        this(pool, keyPrefix, ttlSeconds, false);
    }

    private RedisRetrievalCache(JedisPool pool, String keyPrefix, int ttlSeconds, boolean ownsPool) {
        this.pool = pool;
        this.ownsPool = ownsPool;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "aia:retrieval:" : keyPrefix;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public Optional<List<SearchHit>> get(String scope, String query) {
        String normalized = QuestionNormalizer.normalize(query);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        String raw;
        try (Jedis jedis = pool.getResource()) {
            raw = jedis.get(key(scope, query));
        }
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(raw, HIT_LIST));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void put(String scope, String query, List<SearchHit> hits) {
        String normalized = QuestionNormalizer.normalize(query);
        if (normalized.isEmpty() || hits == null) {
            return;
        }
        try {
            String json = MAPPER.writeValueAsString(hits);
            try (Jedis jedis = pool.getResource()) {
                String key = key(scope, query);
                jedis.set(key, json);
                if (ttlSeconds > 0) {
                    jedis.expire(key, ttlSeconds);
                }
            }
        } catch (RuntimeException e) {
            // 缓存写失败不影响主流程
        } catch (Exception e) {
            // 序列化失败同样忽略
        }
    }

    @Override
    public long version() {
        try (Jedis jedis = pool.getResource()) {
            String raw = jedis.get(versionKey());
            return raw == null ? 0L : Long.parseLong(raw);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    @Override
    public void clear() {
        try (Jedis jedis = pool.getResource()) {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(keyPrefix + "*").count(200);
            List<String> batch = new ArrayList<>();
            do {
                ScanResult<String> page = jedis.scan(cursor, params);
                batch.addAll(page.getResult());
                cursor = page.getCursor();
                if (batch.size() >= 200) {
                    jedis.del(batch.toArray(new String[0]));
                    batch.clear();
                }
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
            if (!batch.isEmpty()) {
                jedis.del(batch.toArray(new String[0]));
            }
            // 递增版本号，通知其它实例清掉自己的本地缓存
            jedis.incr(versionKey());
        } catch (RuntimeException e) {
            // 清缓存失败不影响主流程
        }
    }

    private String versionKey() {
        return keyPrefix + VERSION_SUFFIX;
    }

    @Override
    public void close() {
        if (ownsPool) {
            pool.close();
        }
    }

    private String key(String scope, String query) {
        return keyPrefix + sha256((scope == null ? "" : scope) + "|" + QuestionNormalizer.normalize(query));
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
