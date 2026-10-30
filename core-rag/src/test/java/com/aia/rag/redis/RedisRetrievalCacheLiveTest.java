package com.aia.rag.redis;

import com.aia.rag.SearchHit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 Redis 的检索缓存验证，默认跳过。
 *   mvn test -Dtest=RedisRetrievalCacheLiveTest -Daia.redis.host=192.168.5.15 \
 *            -Daia.redis.password=xxx -Dsurefire.failIfNoSpecifiedTests=false
 */
class RedisRetrievalCacheLiveTest {

    private RedisRetrievalCache cache;

    @BeforeEach
    void setUp() {
        String host = System.getProperty("aia.redis.host");
        assumeTrue(host != null && !host.isBlank(), "未配置 aia.redis.host，跳过真实 Redis 测试");
        cache = new RedisRetrievalCache(host,
                Integer.parseInt(System.getProperty("aia.redis.port", "6379")),
                System.getProperty("aia.redis.password", ""),
                "aia:retrieval:smoke:", 60);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.clear();
            cache.close();
        }
    }

    @Test
    void roundTripsHits() {
        cache.put("topK=3", "退款怎么退", List.of(
                new SearchHit("d1#0", "签收后 7 天内可退", 0.88f, Map.of("docId", "d1"))));

        List<SearchHit> hits = cache.get("topK=3", "退款怎么退？").orElseThrow();

        assertEquals(1, hits.size());
        assertEquals("d1#0", hits.get(0).id());
        assertEquals(0.88f, hits.get(0).score(), 0.0001);
        assertEquals("d1", hits.get(0).metadata().get("docId"));
    }

    @Test
    void differentScopeDoesNotHit() {
        cache.put("topK=3", "退款怎么退", List.of(new SearchHit("d1#0", "x", 1f, Map.of())));

        assertTrue(cache.get("topK=5", "退款怎么退").isEmpty());
    }

    @Test
    void clearRemovesEntries() {
        cache.put("topK=3", "q", List.of(new SearchHit("d1#0", "x", 1f, Map.of())));

        cache.clear();

        assertTrue(cache.get("topK=3", "q").isEmpty());
    }
}
