package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.rag.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "agentloom.rag.enabled=true",
        "agentloom.rag.embedding.provider=ollama",
        "agentloom.rag.embedding.dimension=512"
})
class EmbeddingDimensionOverrideTest {

    @Autowired
    EmbeddingModel embeddingModel;

    @Test
    void explicitDimensionOverridesPreset() {
        assertEquals(512, embeddingModel.dimension());
    }
}
