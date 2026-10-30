package com.aia.rag.redis;

import com.aia.rag.SearchHit;
import com.aia.rag.VectorQuery;
import com.aia.rag.VectorRecord;
import com.aia.rag.VectorStore;
import com.aia.rag.VectorStoreCapability;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.commands.ProtocolCommand;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 Redis 8+ 原生 Vector Set（{@code VADD} / {@code VSIM}）的向量存储实现，
 * 不需要 Redis Stack / RediSearch。
 *
 * <p>每个 chunk 是一个 element：向量以 FP32 小端二进制写入，正文与元数据存在属性里，
 * 检索分数为 Redis 返回的余弦相似度（1 表示完全一致）。
 */
public final class RedisVectorStore implements VectorStore, AutoCloseable {

    private static final ProtocolCommand VADD = () -> "VADD".getBytes(StandardCharsets.UTF_8);
    private static final ProtocolCommand VSIM = () -> "VSIM".getBytes(StandardCharsets.UTF_8);
    private static final ProtocolCommand VREM = () -> "VREM".getBytes(StandardCharsets.UTF_8);

    private final JedisPool pool;
    private final boolean ownsPool;
    private final byte[] key;
    private final int dimension;

    public RedisVectorStore(String host, int port, String password, String key, int dimension) {
        this(new JedisPool(new HostAndPort(host, port),
                DefaultJedisClientConfig.builder().password(password).build()), key, dimension, true);
    }

    public RedisVectorStore(JedisPool pool, String key, int dimension) {
        this(pool, key, dimension, false);
    }

    private RedisVectorStore(JedisPool pool, String key, int dimension, boolean ownsPool) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("向量维度必须大于 0");
        }
        this.pool = pool;
        this.ownsPool = ownsPool;
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.dimension = dimension;
    }

    @Override
    public void upsert(List<VectorRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        for (VectorRecord record : records) {
            checkDimension(record.vector());
        }
        try (Jedis jedis = pool.getResource()) {
            for (VectorRecord record : records) {
                jedis.sendCommand(VADD,
                        key,
                        "FP32".getBytes(StandardCharsets.UTF_8),
                        RedisVectorCodec.toFloat32(record.vector()),
                        record.id().getBytes(StandardCharsets.UTF_8),
                        "SETATTR".getBytes(StandardCharsets.UTF_8),
                        RedisVectorCodec.recordAttributes(record).getBytes(StandardCharsets.UTF_8));
            }
        } catch (RuntimeException e) {
            throw new RedisVectorStoreException("写入 Redis Vector Set 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SearchHit> search(VectorQuery query) {
        checkDimension(query.vector());
        List<byte[]> args = new ArrayList<>();
        args.add(key);
        args.add("FP32".getBytes(StandardCharsets.UTF_8));
        args.add(RedisVectorCodec.toFloat32(query.vector()));
        args.add("WITHSCORES".getBytes(StandardCharsets.UTF_8));
        args.add("WITHATTRIBS".getBytes(StandardCharsets.UTF_8));
        args.add("COUNT".getBytes(StandardCharsets.UTF_8));
        args.add(String.valueOf(query.topK()).getBytes(StandardCharsets.UTF_8));
        String filter = RedisVectorCodec.filterExpression(query.filter());
        if (!filter.isEmpty()) {
            args.add("FILTER".getBytes(StandardCharsets.UTF_8));
            args.add(filter.getBytes(StandardCharsets.UTF_8));
        }

        Object reply;
        try (Jedis jedis = pool.getResource()) {
            reply = jedis.sendCommand(VSIM, args.toArray(new byte[0][]));
        } catch (RuntimeException e) {
            throw new RedisVectorStoreException("检索 Redis Vector Set 失败: " + e.getMessage(), e);
        }
        return parseHits(reply);
    }

    @Override
    public void delete(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            // VREM 一次只接受一个元素，逐个删除
            for (String id : ids) {
                jedis.sendCommand(VREM, key, id.getBytes(StandardCharsets.UTF_8));
            }
        } catch (RuntimeException e) {
            throw new RedisVectorStoreException("删除 Redis Vector Set 元素失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void update(List<VectorRecord> records) {
        upsert(records);
    }

    @Override
    public Set<VectorStoreCapability> capabilities() {
        return Set.of(VectorStoreCapability.METADATA_FILTER, VectorStoreCapability.BATCH_UPSERT);
    }

    @Override
    public void close() {
        if (ownsPool) {
            pool.close();
        }
    }

    /**
     * VSIM 的回复是扁平的 [element, score, attribs, element, score, attribs ...]。
     */
    private List<SearchHit> parseHits(Object reply) {
        List<SearchHit> hits = new ArrayList<>();
        if (!(reply instanceof List<?> list)) {
            return hits;
        }
        int stride = 3;   // element + score + attribs
        for (int i = 0; i + 1 < list.size(); i += stride) {
            String id = asString(list.get(i));
            float score = Float.parseFloat(asString(list.get(i + 1)));
            ParsedAttributes attributes = i + 2 < list.size() && list.get(i + 2) != null
                    ? RedisVectorCodec.parseAttributes(asString(list.get(i + 2)))
                    : new ParsedAttributes("", Map.of());
            hits.add(new SearchHit(id, attributes.text(), score, attributes.metadata()));
        }
        return hits;
    }

    private static String asString(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private void checkDimension(float[] vector) {
        if (vector == null || vector.length != dimension) {
            throw new IllegalArgumentException("向量维度不匹配: 期望 " + dimension
                    + (vector == null ? "" : " 实际 " + vector.length));
        }
    }
}
