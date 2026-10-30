package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.rag.ElasticsearchVectorStore;
import io.github.dongcs9512.agentloom.rag.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(properties = {
        "agentloom.rag.enabled=true",
        "agentloom.rag.embedding.provider=ollama",
        "agentloom.rag.store=es",
        "agentloom.rag.es.url=http://localhost:9200"
})
class EsVectorStoreSelectionTest {

    @Autowired
    VectorStore vectorStore;

    @Test
    void elasticsearchIsSelectedByConfig() {
        assertInstanceOf(ElasticsearchVectorStore.class, vectorStore);
    }
}
