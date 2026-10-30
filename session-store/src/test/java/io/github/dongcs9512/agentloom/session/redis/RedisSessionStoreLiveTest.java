package io.github.dongcs9512.agentloom.session.redis;

import io.github.dongcs9512.agentloom.core.session.Session;
import io.github.dongcs9512.agentloom.llm.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 针对真实 Redis 的会话存储验证，默认跳过。
 *   mvn test -Dtest=RedisSessionStoreLiveTest -Dagentloom.redis.host=localhost \
 *            -Dagentloom.redis.password=xxx -Dsurefire.failIfNoSpecifiedTests=false
 */
class RedisSessionStoreLiveTest {

    private RedisSessionStore store;

    @BeforeEach
    void setUp() {
        String host = System.getProperty("agentloom.redis.host");
        assumeTrue(host != null && !host.isBlank(), "未配置 aia.redis.host，跳过真实 Redis 会话测试");
        store = new RedisSessionStore(host,
                Integer.parseInt(System.getProperty("agentloom.redis.port", "6379")),
                System.getProperty("agentloom.redis.password", ""),
                "agentloom:session:smoke:", 60);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.delete("s1");
            store.close();
        }
    }

    @Test
    void roundTripsThroughRedis() {
        Session session = new Session("s1", "a1");
        session.addMessage(ChatMessage.user("我要咨询劳动纠纷"));
        session.putSlot("incident", "被辞退");
        session.replaceHistory("用户咨询劳动纠纷", session.messages());

        store.save(session);
        Session loaded = store.load("s1").orElseThrow();

        assertEquals("a1", loaded.agentId());
        assertEquals("用户咨询劳动纠纷", loaded.summary());
        assertEquals("被辞退", loaded.slot("incident"));
        assertEquals(1, loaded.messages().size());
        assertEquals("我要咨询劳动纠纷", loaded.messages().get(0).content());
    }

    @Test
    void deleteRemovesKey() {
        Session session = new Session("s1", "a1");
        store.save(session);

        store.delete("s1");

        assertTrue(store.load("s1").isEmpty());
    }
}
