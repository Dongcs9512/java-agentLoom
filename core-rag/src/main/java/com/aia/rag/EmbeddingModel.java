package com.aia.rag;

public interface EmbeddingModel {
    float[] embed(String text);

    int dimension();
}
