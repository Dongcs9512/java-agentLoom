package io.github.dongcs9512.agentloom.rag;

import java.util.Map;

public record SearchHit(String id, String text, float score, Map<String, Object> metadata) {}
