package io.github.dongcs9512.agentloom.rag.redis;

import io.github.dongcs9512.agentloom.rag.SearchHit;
import io.github.dongcs9512.agentloom.rag.VectorQuery;
import io.github.dongcs9512.agentloom.rag.VectorRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 针对真实 Redis（8+，需内置 vectorset 模块）的端到端验证，默认跳过。
 * 运行方式:
 *   mvn test -Dtest=RedisVectorStoreLiveTest \
 *            -Daia.redis.host=192.168.5.15 -Daia.redis.password=xxx \
 *            -Dsurefire.failIfNoSpecifiedTests=false
 */
class RedisVectorStoreLiveTest {

    private RedisVectorStore store;

    @BeforeEach
    void setUp() {
        String host = System.getProperty("agentloom.redis.host");
        assumeTrue(host != null && !host.isBlank(), "未配置 aia.redis.host，跳过真实 Redis 冒烟测试");
        store = new RedisVectorStore(host,
                Integer.parseInt(System.getProperty("agentloom.redis.port", "6379")),
                System.getProperty("agentloom.redis.password", ""),
                "agentloom-redis-smoke", 3);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.delete(List.of("a", "b", "c"));
            store.close();
        }
    }

    @Test
    void writeSearchFilterDeleteRoundTrip() {
        store.upsert(List.of(
                new VectorRecord("a", "退款政策", new float[]{1f, 0f, 0f}, Map.of("docId", "g1")),
                new VectorRecord("b", "发货说明", new float[]{0f, 1f, 0f}, Map.of("docId", "g2")),
                new VectorRecord("c", "发票说明", new float[]{0f, 0f, 1f}, Map.of("docId", "g2"))));

        List<SearchHit> hits = store.search(new VectorQuery(new float[]{1f, 0f, 0f}, 3, Map.of()));
        assertEquals(3, hits.size());
        assertEquals("a", hits.get(0).id());
        assertEquals("退款政策", hits.get(0).text());
        assertEquals("g1", hits.get(0).metadata().get("docId"));
        assertTrue(hits.get(0).score() > hits.get(1).score(), "最相似项应排在首位");

        List<SearchHit> filtered = store.search(new VectorQuery(new float[]{1f, 0f, 0f}, 3, Map.of("docId", "g2")));
        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(h -> "g2".equals(h.metadata().get("docId"))));

        store.delete(List.of("a"));
        List<SearchHit> after = store.search(new VectorQuery(new float[]{1f, 0f, 0f}, 3, Map.of()));
        assertEquals(2, after.size());
        assertTrue(after.stream().noneMatch(h -> "a".equals(h.id())));
    }
}
