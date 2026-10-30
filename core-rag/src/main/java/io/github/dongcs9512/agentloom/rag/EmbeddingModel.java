package io.github.dongcs9512.agentloom.rag;

import java.util.ArrayList;
import java.util.List;

public interface EmbeddingModel {
    float[] embed(String text);

    int dimension();

    /**
     * 批量向量化。默认实现逐条调用 {@link #embed(String)}；支持批量协议的实现应覆盖此方法以减少往返。
     */
    default List<float[]> embedBatch(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }
}
