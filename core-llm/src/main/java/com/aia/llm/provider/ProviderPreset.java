package com.aia.llm.provider;

/**
 * 一家模型供应商的接入预设：OpenAI 兼容的 baseUrl + 默认对话 / 向量化 / 重排模型。
 *
 * @param embeddingModel     为 null 表示该供应商不提供向量化服务（如 DeepSeek）
 * @param embeddingDimension 默认维度，0 表示未知，需显式指定
 * @param rerankModel        为 null 表示该供应商没有 OpenAI 兼容的重排接口
 */
public record ProviderPreset(
        String name,
        String baseUrl,
        String chatModel,
        String embeddingModel,
        int embeddingDimension,
        String rerankModel) {

    public boolean supportsEmbedding() {
        return embeddingModel != null && !embeddingModel.isBlank();
    }

    public boolean supportsRerank() {
        return rerankModel != null && !rerankModel.isBlank();
    }
}
