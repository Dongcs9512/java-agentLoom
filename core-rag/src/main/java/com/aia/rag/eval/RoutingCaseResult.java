package com.aia.rag.eval;

import java.util.List;

/**
 * @param correct    是否分派正确（期望为 null 时，actual 为 null 才算对）
 * @param latencyMs  本次路由耗时
 */
public record RoutingCaseResult(String query, String expected, String actual, boolean correct, long latencyMs) {
}
