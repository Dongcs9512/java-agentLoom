package com.aia.rag;

/**
 * @param index 候选文档在请求列表中的下标
 * @param score 相关性分数，越大越相关
 */
public record RerankResult(int index, double score) {}
