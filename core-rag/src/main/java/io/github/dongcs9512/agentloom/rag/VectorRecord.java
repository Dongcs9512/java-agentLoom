package io.github.dongcs9512.agentloom.rag;

import java.util.Map;

public record VectorRecord(String id, String text, float[] vector, Map<String, Object> metadata) {}
