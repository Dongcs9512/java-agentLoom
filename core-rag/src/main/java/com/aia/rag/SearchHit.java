package com.aia.rag;

import java.util.Map;

public record SearchHit(String id, String text, float score, Map<String, Object> metadata) {}
