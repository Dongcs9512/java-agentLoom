package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.rag.InMemoryVectorStore;
import io.github.dongcs9512.agentloom.rag.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(properties = {
        "agentloom.rag.enabled=true",
        "agentloom.rag.embedding.provider=ollama",
        "agentloom.rag.store=memory"
})
class MemoryVectorStoreSelectionTest {

    @Autowired
    VectorStore vectorStore;

    @Test
    void memoryIsDefaultChoice() {
        assertInstanceOf(InMemoryVectorStore.class, vectorStore);
    }
}
