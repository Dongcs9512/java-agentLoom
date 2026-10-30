package com.aia.rag.eval;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 单个路由策略的成绩单。
 */
public record RoutingStrategyReport(
        String strategy,
        double accuracy,
        double avgLatencyMs,
        long p95LatencyMs,
        List<RoutingCaseResult> cases) {

    public RoutingStrategyReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public static RoutingStrategyReport of(String strategy, List<RoutingCaseResult> cases) {
        if (cases.isEmpty()) {
            return new RoutingStrategyReport(strategy, 0, 0, 0, cases);
        }
        long correct = cases.stream().filter(RoutingCaseResult::correct).count();
        double accuracy = (double) correct / cases.size();
        double avg = cases.stream().mapToLong(RoutingCaseResult::latencyMs).average().orElse(0);
        List<Long> sorted = cases.stream().map(RoutingCaseResult::latencyMs).sorted().toList();
        long p95 = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1));
        return new RoutingStrategyReport(strategy, accuracy, avg, p95, cases);
    }

    public List<String> mistakes() {
        return cases.stream()
                .filter(c -> !c.correct())
                .map(c -> String.format(Locale.ROOT, "%s → 期望 %s 实际 %s",
                        c.query(), c.expected() == null ? "无法归类" : c.expected(),
                        c.actual() == null ? "无法归类" : c.actual()))
                .toList();
    }

    public String summary() {
        return String.format(Locale.ROOT, "%-10s 准确率 %5.1f%%  平均 %6.1fms  p95 %5dms",
                strategy, accuracy * 100, avgLatencyMs, p95LatencyMs);
    }

    public static List<RoutingStrategyReport> sortedByAccuracyThenSpeed(List<RoutingStrategyReport> reports) {
        return reports.stream()
                .sorted(Comparator.comparingDouble(RoutingStrategyReport::accuracy).reversed()
                        .thenComparingDouble(RoutingStrategyReport::avgLatencyMs))
                .toList();
    }
}
