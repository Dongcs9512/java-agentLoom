package io.github.dongcs9512.agentloom.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 并发去重：相同 key 的请求同时进来时只让一个真正执行，其余等它的结果。
 *
 * <p>典型场景是热点问题被同时问爆（缓存刚过期、活动开始瞬间），
 * 不加这层会并发调用 N 次模型——既浪费配额，又更容易触发上游限流。
 */
public final class SingleFlight<K, V> {

    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final long waitMillis;

    public SingleFlight(long waitMillis) {
        this.waitMillis = waitMillis;
    }

    public V execute(K key, Supplier<V> action) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return await(key, running);
        }
        try {
            V value = action.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private V await(K key, CompletableFuture<V> running) {
        try {
            return running.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("等待同一请求的结果超时: " + key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待同一请求的结果被中断: " + key);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause == null ? "同一请求执行失败" : cause.getMessage(), cause);
        }
    }

    public int inFlightCount() {
        return inFlight.size();
    }
}
