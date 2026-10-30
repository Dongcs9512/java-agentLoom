package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.rag.eval.RoutingCaseResult;
import io.github.dongcs9512.agentloom.rag.eval.RoutingStrategyReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingScoringTest {

    @Test
    void exactMatchIsCorrect() {
        assertTrue(RoutingBenchmarkService.judge("legal-labor", List.of(), "legal-labor"));
    }

    @Test
    void acceptableAlternativeCountsAsCorrect() {
        assertTrue(RoutingBenchmarkService.judge("legal-welfare", List.of("legal-labor"), "legal-labor"));
    }

    @Test
    void wrongAgentIsIncorrect() {
        assertFalse(RoutingBenchmarkService.judge("legal-welfare", List.of("legal-labor"), "legal-contract"));
    }

    @Test
    void nullExpectedMeansUnclassifiedIsCorrect() {
        assertTrue(RoutingBenchmarkService.judge(null, List.of(), null));
        assertFalse(RoutingBenchmarkService.judge(null, List.of(), "legal-labor"));
    }

    @Test
    void strategyReportComputesAccuracyAverageAndP95() {
        List<RoutingCaseResult> cases = List.of(
                new RoutingCaseResult("q1", "a", "a", true, 1),
                new RoutingCaseResult("q2", "a", "b", false, 3),
                new RoutingCaseResult("q3", "a", "a", true, 5),
                new RoutingCaseResult("q4", "a", "a", true, 7));

        RoutingStrategyReport report = RoutingStrategyReport.of("keyword", cases);

        assertEquals(0.75, report.accuracy(), 0.0001);
        assertEquals(4.0, report.avgLatencyMs(), 0.0001);
        assertEquals(7, report.p95LatencyMs());
        assertEquals(1, report.mistakes().size());
    }

    @Test
    void rankingPrefersAccuracyThenSpeed() {
        RoutingStrategyReport accurateSlow = RoutingStrategyReport.of("slow-accurate", List.of(
                new RoutingCaseResult("q1", "a", "a", true, 900),
                new RoutingCaseResult("q2", "a", "a", true, 900)));
        RoutingStrategyReport fastLessAccurate = RoutingStrategyReport.of("fast", List.of(
                new RoutingCaseResult("q1", "a", "a", true, 1),
                new RoutingCaseResult("q2", "a", "b", false, 1)));

        List<RoutingStrategyReport> ranked = RoutingStrategyReport.sortedByAccuracyThenSpeed(
                List.of(fastLessAccurate, accurateSlow));

        assertEquals("slow-accurate", ranked.get(0).strategy());
    }
}
