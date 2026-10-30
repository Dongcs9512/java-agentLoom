package io.github.dongcs9512.agentloom.rag.redis;

import io.github.dongcs9512.agentloom.rag.CompositeRetrievalCache;
import io.github.dongcs9512.agentloom.rag.InMemoryRetrievalCache;
import io.github.dongcs9512.agentloom.rag.SearchHit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 Redis 的失效广播验证，默认跳过。
 *   mvn test -Dtest=RedisInvalidationSubscriberLiveTest -Daia.redis.host=192.168.5.15 \
 *            -Daia.redis.password=xxx -Dsurefire.failIfNoSpecifiedTests=false
 */
class RedisInvalidationSubscriberLiveTest {

    private static final String PREFIX = "agentloom:retrieval:pubsub:";

    private String host;
    private int port;
    private String password;
    private RedisRetrievalCache sharedA;
    private RedisRetrievalCache sharedB;
    private RedisInvalidationSubscriber subscriber;

    @BeforeEach
    void setUp() {
        host = System.getProperty("agentloom.redis.host");
        assumeTrue(host != null && !host.isBlank(), "未配置 aia.redis.host，跳过真实 Redis 测试");
        port = Integer.parseInt(System.getProperty("agentloom.redis.port", "6379"));
        password = System.getProperty("agentloom.redis.password", "");
        sharedA = new RedisRetrievalCache(host, port, password, PREFIX, 60);
        sharedB = new RedisRetrievalCache(host, port, password, PREFIX, 60);
    }

    @AfterEach
    void tearDown() {
        if (subscriber != null) {
            subscriber.close();
        }
        if (sharedA != null) {
            sharedA.clear();
            sharedA.close();
        }
        if (sharedB != null) {
            sharedB.close();
        }
    }

    private static List<SearchHit> hits(String text) {
        return List.of(new SearchHit("d1#0", text, 0.9f, Map.of("docId", "d1")));
    }

    @Test
    void broadcastClearsOtherInstanceLocalCache() throws Exception {
        InMemoryRetrievalCache localB = new InMemoryRetrievalCache(20, 0);
        // 版本轮询间隔设得很长，确保只有广播能触发失效
        CompositeRetrievalCache instanceB = new CompositeRetrievalCache(localB, sharedB, 60_000);

        subscriber = new RedisInvalidationSubscriber(host, port, password,
                sharedB.invalidationChannel(), localB::clear);
        subscriber.start();
        Thread.sleep(500);

        sharedA.put("topK=4", "试用期被辞退", hits("旧内容"));
        assertEquals("旧内容", instanceB.get("topK=4", "试用期被辞退").orElseThrow().get(0).text());

        // 另一个实例写入知识库 → 清共享缓存并广播
        sharedA.clear();
        Thread.sleep(400);

        sharedA.put("topK=4", "试用期被辞退", hits("新内容"));
        String latest = instanceB.get("topK=4", "试用期被辞退").orElseThrow().get(0).text();

        assertEquals("新内容", latest, "本地缓存应被广播实时清掉");
    }
}
