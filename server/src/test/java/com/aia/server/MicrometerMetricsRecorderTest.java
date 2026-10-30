package com.aia.server;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MicrometerMetricsRecorderTest {

    @Test
    void countsByTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

        recorder.count("aia.cache.retrieval", Map.of("result", "hit"));
        recorder.count("aia.cache.retrieval", Map.of("result", "hit"));
        recorder.count("aia.cache.retrieval", Map.of("result", "miss"));

        assertEquals(2.0, registry.counter("aia.cache.retrieval", "result", "hit").count(), 0.0001);
        assertEquals(1.0, registry.counter("aia.cache.retrieval", "result", "miss").count(), 0.0001);
    }

    @Test
    void recordsTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

        recorder.time("aia.rag.search", 120, Map.of("rerank", "true"));

        assertEquals(1, registry.timer("aia.rag.search", "rerank", "true").count());
        assertEquals(120.0, registry.timer("aia.rag.search", "rerank", "true").totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    void measureRecordsOutcomeAndRethrows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

        recorder.measure("aia.agent.chat", Map.of("agent", "a1"), () -> "ok");
        try {
            recorder.measure("aia.agent.chat", Map.of("agent", "a1"), () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException ignored) {
            // 预期异常
        }

        assertEquals(1, registry.timer("aia.agent.chat", "agent", "a1", "outcome", "ok").count());
        assertEquals(1, registry.timer("aia.agent.chat", "agent", "a1", "outcome", "error").count());
    }
}
