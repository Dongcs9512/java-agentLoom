package com.aia.server;

import com.aia.rag.VectorStore;
import com.aia.rag.redis.RedisVectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(properties = {
        "aia.rag.enabled=true",
        "aia.rag.embedding.provider=ollama",
        "aia.rag.store=redis",
        "aia.rag.redis.host=localhost",
        "aia.rag.redis.password=test-password"
})
class RedisVectorStoreSelectionTest {

    @Autowired
    VectorStore vectorStore;

    @Test
    void redisIsSelectedByConfig() {
        assertInstanceOf(RedisVectorStore.class, vectorStore);
    }
}
