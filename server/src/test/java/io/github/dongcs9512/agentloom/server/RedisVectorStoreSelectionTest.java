package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.rag.VectorStore;
import io.github.dongcs9512.agentloom.rag.redis.RedisVectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(properties = {
        "agentloom.rag.enabled=true",
        "agentloom.rag.embedding.provider=ollama",
        "agentloom.rag.store=redis",
        "agentloom.rag.redis.host=localhost",
        "agentloom.rag.redis.password=test-password"
})
class RedisVectorStoreSelectionTest {

    @Autowired
    VectorStore vectorStore;

    @Test
    void redisIsSelectedByConfig() {
        assertInstanceOf(RedisVectorStore.class, vectorStore);
    }
}
