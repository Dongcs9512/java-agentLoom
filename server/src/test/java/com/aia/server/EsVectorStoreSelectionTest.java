package com.aia.server;

import com.aia.rag.ElasticsearchVectorStore;
import com.aia.rag.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(properties = {
        "aia.rag.enabled=true",
        "aia.rag.embedding.provider=ollama",
        "aia.rag.store=es",
        "aia.rag.es.url=http://localhost:9200"
})
class EsVectorStoreSelectionTest {

    @Autowired
    VectorStore vectorStore;

    @Test
    void elasticsearchIsSelectedByConfig() {
        assertInstanceOf(ElasticsearchVectorStore.class, vectorStore);
    }
}
