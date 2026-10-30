package io.github.dongcs9512.agentloom.example;

import io.github.dongcs9512.agentloom.rag.EmbeddingModel;

/**
 * 仅供示例离线运行使用的哈希向量模型：把字符散列到固定维度。
 *
 * <p>它能跑通"写入 → 检索"的完整链路，但**没有语义能力**——
 * 只有字符重叠多的文本才会相似。真实项目请换成
 * {@code OpenAiCompatibleEmbeddingModel}（通义 / 硅基流动 / Ollama 都支持）。
 */
public final class DemoEmbeddingModel implements EmbeddingModel {

    private final int dimension;

    public DemoEmbeddingModel(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimension];
        if (text == null || text.isEmpty()) {
            vector[0] = 1f;
            return vector;
        }
        for (int i = 0; i < text.length(); i++) {
            int bucket = Math.floorMod(Character.hashCode(text.charAt(i)) + i, dimension);
            vector[bucket] += 1f;
        }
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        if (norm > 0) {
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < dimension; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }

    @Override
    public int dimension() {
        return dimension;
    }
}
