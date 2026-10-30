package com.aia.llm.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvidersTest {

    @Test
    void siliconflowPresetCarriesQwen3EmbeddingDefaults() {
        ProviderPreset p = Providers.require("siliconflow");

        assertEquals("https://api.siliconflow.cn/v1", p.baseUrl());
        assertEquals("Qwen/Qwen3-Embedding-0.6B", p.embeddingModel());
        assertEquals(1024, p.embeddingDimension());
    }

    @Test
    void lookupIsCaseInsensitiveAndTrims() {
        assertEquals("dashscope", Providers.require("  DashScope ").name());
    }

    @Test
    void unknownProviderReturnsEmpty() {
        assertTrue(Providers.byName("nope").isEmpty());
    }

    @Test
    void requireThrowsForUnknownProvider() {
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> Providers.require("nope"));
        assertTrue(ex.getMessage().contains("nope"), ex.getMessage());
    }

    @Test
    void deepseekHasNoEmbeddingModel() {
        assertNull(Providers.require("deepseek").embeddingModel());
    }

    @Test
    void everyPresetHasChatAndBaseUrl() {
        for (ProviderPreset preset : Providers.all()) {
            assertTrue(preset.baseUrl().startsWith("http"), preset.name());
            assertTrue(preset.chatModel() != null && !preset.chatModel().isBlank(), preset.name());
        }
    }

    @Test
    void allReturnsKnownProviders() {
        List<String> names = Providers.all().stream().map(ProviderPreset::name).toList();
        assertTrue(names.containsAll(List.of("deepseek", "dashscope", "siliconflow", "ollama", "openai")), names.toString());
    }

    @Test
    void customProviderCanBeRegistered() {
        Providers.register(new ProviderPreset("my-llm", "https://my.example.com/v1", "my-chat", "my-embed", 512, null));

        ProviderPreset p = Providers.require("my-llm");
        assertEquals(512, p.embeddingDimension());
        assertEquals("my-embed", p.embeddingModel());
    }

    @Test
    void siliconflowProvidesRerankModel() {
        ProviderPreset p = Providers.require("siliconflow");

        assertTrue(p.supportsRerank());
        assertEquals("BAAI/bge-reranker-v2-m3", p.rerankModel());
    }

    @Test
    void providersWithoutRerankReportIt() {
        org.junit.jupiter.api.Assertions.assertFalse(Providers.require("deepseek").supportsRerank());
        org.junit.jupiter.api.Assertions.assertFalse(Providers.require("openai").supportsRerank());
    }
}
