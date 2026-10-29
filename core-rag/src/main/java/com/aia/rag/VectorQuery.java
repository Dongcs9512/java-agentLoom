package com.aia.rag;

import java.util.Map;

public record VectorQuery(float[] vector, int topK, Map<String, Object> filter) {}
