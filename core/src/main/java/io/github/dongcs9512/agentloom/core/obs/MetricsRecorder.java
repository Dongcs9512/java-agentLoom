package io.github.dongcs9512.agentloom.core.obs;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 指标记录 SPI。核心库不依赖任何监控框架，由宿主（Spring/Micrometer、Dropwizard 等）提供实现；
 * 不关心的场景直接用默认的空实现即可。
 */
public interface MetricsRecorder {

    /** 计数一次。 */
    default void count(String name, Map<String, String> tags) {
        count(name, 1, tags);
    }

    /** 累加计数（例如 token 数这种不是每次 +1 的场景）。 */
    default void count(String name, long amount, Map<String, String> tags) {
    }

    /** 记录一次耗时（毫秒）。 */
    default void time(String name, long millis, Map<String, String> tags) {
    }

    /** 便捷方法：包住一段逻辑并记录耗时与结果。 */
    default <T> T measure(String name, Map<String, String> tags, Supplier<T> action) {
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            return action.get();
        } catch (RuntimeException | Error e) {
            outcome = "error";
            throw e;
        } finally {
            java.util.Map<String, String> merged = new java.util.HashMap<>(tags == null ? Map.of() : tags);
            merged.put("outcome", outcome);
            time(name, Math.round((System.nanoTime() - start) / 1_000_000.0), merged);
        }
    }

    static MetricsRecorder noop() {
        return new MetricsRecorder() {
        };
    }
}
