package io.github.dongcs9512.agentloom.rag.redis;

import java.util.Map;

/**
 * @param text     chunk 正文
 * @param metadata 除正文外的其余属性
 */
public record ParsedAttributes(String text, Map<String, Object> metadata) {}
