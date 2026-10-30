package com.aia.server;

import com.aia.rag.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "aia.rag.enabled=true",
        "aia.rag.embedding.provider=ollama",
        "aia.rag.embedding.dimension=512"
})
class EmbeddingDimensionOverrideTest {

    @Autowired
    EmbeddingModel embeddingModel;

    @Test
    void explicitDimensionOverridesPreset() {
        assertEquals(512, embeddingModel.dimension());
    }
}
