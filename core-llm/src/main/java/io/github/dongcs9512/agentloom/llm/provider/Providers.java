package io.github.dongcs9512.agentloom.llm.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 常用模型供应商的内置预设。所有供应商都通过 OpenAI 兼容协议接入，
 * 差别只在 baseUrl、模型名和向量维度。
 *
 * <p>接入不在内置列表里的供应商时，用 {@link #register(ProviderPreset)} 注册即可，
 * 或者直接构造 {@code OpenAiCompatibleChatModel} / {@code OpenAiCompatibleEmbeddingModel}。
 */
public final class Providers {

    public static final ProviderPreset DEEPSEEK = new ProviderPreset(
            "deepseek", "https://api.deepseek.com/v1", "deepseek-chat", null, 0, null);

    public static final ProviderPreset DASHSCOPE = new ProviderPreset(
            "dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen-plus", "text-embedding-v3", 1024, null);

    public static final ProviderPreset SILICONFLOW = new ProviderPreset(
            "siliconflow", "https://api.siliconflow.cn/v1",
            "Qwen/Qwen2.5-7B-Instruct", "Qwen/Qwen3-Embedding-0.6B", 1024, "BAAI/bge-reranker-v2-m3");

    public static final ProviderPreset OPENAI = new ProviderPreset(
            "openai", "https://api.openai.com/v1", "gpt-4o-mini", "text-embedding-3-small", 1536, null);

    public static final ProviderPreset OLLAMA = new ProviderPreset(
            "ollama", "http://localhost:11434/v1", "qwen2.5", "nomic-embed-text", 768, null);

    private static final Map<String, ProviderPreset> REGISTRY = new LinkedHashMap<>();

    static {
        for (ProviderPreset preset : List.of(DEEPSEEK, DASHSCOPE, SILICONFLOW, OPENAI, OLLAMA)) {
            REGISTRY.put(preset.name(), preset);
        }
    }

    private Providers() {
    }

    public static Optional<ProviderPreset> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(REGISTRY.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    public static ProviderPreset require(String name) {
        return byName(name).orElseThrow(() -> new IllegalArgumentException(
                "未知的模型供应商: " + name + "，已内置: " + String.join(", ", REGISTRY.keySet())));
    }

    public static void register(ProviderPreset preset) {
        REGISTRY.put(preset.name().toLowerCase(Locale.ROOT), preset);
    }

    public static List<ProviderPreset> all() {
        return List.copyOf(REGISTRY.values());
    }
}
