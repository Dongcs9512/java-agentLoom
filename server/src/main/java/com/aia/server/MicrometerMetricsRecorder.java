package com.aia.server;

import com.aia.core.obs.MetricsRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MeterRegistry 实现：计数与耗时打到 Micrometer，再由 Prometheus 端点暴露出去。
 *
 * <p>meter 按「名字 + 标签」缓存，避免每次请求都重新构建（高频路径上这点开销不可忽视）。
 */
@Component
public class MicrometerMetricsRecorder implements MetricsRecorder {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();

    public MicrometerMetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void count(String name, Map<String, String> tags) {
        String key = key(name, tags);
        counters.computeIfAbsent(key, k -> Counter.builder(name).tags(toTags(tags)).register(registry))
                .increment();
    }

    @Override
    public void time(String name, long millis, Map<String, String> tags) {
        String key = key(name, tags);
        timers.computeIfAbsent(key, k -> Timer.builder(name).tags(toTags(tags)).register(registry))
                .record(millis, TimeUnit.MILLISECONDS);
    }

    private static String key(String name, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name);
        new java.util.TreeMap<>(tags).forEach((k, v) -> sb.append('|').append(k).append('=').append(v));
        return sb.toString();
    }

    private static String[] toTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return new String[0];
        }
        String[] flat = new String[tags.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            flat[i++] = entry.getKey();
            flat[i++] = entry.getValue();
        }
        return flat;
    }
}
