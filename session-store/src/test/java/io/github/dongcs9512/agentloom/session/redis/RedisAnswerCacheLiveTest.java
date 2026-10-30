package io.github.dongcs9512.agentloom.session.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 Redis 答案缓存验证，默认跳过。
 *   mvn test -Dtest=RedisAnswerCacheLiveTest -Daia.redis.host=192.168.5.15 \
 *            -Daia.redis.password=xxx -Dsurefire.failIfNoSpecifiedTests=false
 */
class RedisAnswerCacheLiveTest {

    private RedisAnswerCache cache;

    @BeforeEach
    void setUp() {
        String host = System.getProperty("agentloom.redis.host");
        assumeTrue(host != null && !host.isBlank(), "未配置 aia.redis.host，跳过真实 Redis 缓存测试");
        cache = new RedisAnswerCache(host,
                Integer.parseInt(System.getProperty("agentloom.redis.port", "6379")),
                System.getProperty("agentloom.redis.password", ""),
                "agentloom:answer:smoke:", 60);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void roundTripsThroughRedis() {
        cache.put("a1", "退货怎么退", "签收后 7 天内可退");

        assertEquals("签收后 7 天内可退", cache.get("a1", "退货怎么退？").orElseThrow());
    }

    @Test
    void missForUnknownQuestion() {
        assertTrue(cache.get("a1", "从来没有问过的问题").isEmpty());
    }
}
