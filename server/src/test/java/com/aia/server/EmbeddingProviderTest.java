package com.aia.server;

import com.aia.rag.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 不配置 dimension 时，维度应来自供应商预设（ollama 的 nomic-embed-text 是 768）。
 */
@SpringBootTest(properties = {
        "aia.rag.enabled=true",
        "aia.rag.embedding.provider=ollama"
})
class EmbeddingProviderTest {

    @Autowired
    EmbeddingModel embeddingModel;

    @Test
    void dimensionComesFromProviderPreset() {
        assertEquals(768, embeddingModel.dimension());
    }
}
