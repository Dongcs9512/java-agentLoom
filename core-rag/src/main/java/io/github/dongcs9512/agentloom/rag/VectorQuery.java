package io.github.dongcs9512.agentloom.rag;

import java.util.Map;

public record VectorQuery(float[] vector, int topK, Map<String, Object> filter) {}
