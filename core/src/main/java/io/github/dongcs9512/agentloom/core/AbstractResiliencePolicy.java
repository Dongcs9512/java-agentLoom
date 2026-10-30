package io.github.dongcs9512.agentloom.core;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletableFuture;

public abstract class AbstractResiliencePolicy implements ResiliencePolicy {

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private State state = State.CLOSED;
    private int failureCount = 0;
    private long openedAt = 0L;

    protected Duration timeout() {
        return Duration.ofSeconds(30);
    }

    protected int maxRetries() {
        return 2;
    }

    protected int circuitBreakerThreshold() {
        return 5;
    }

    protected Duration circuitBreakerHalfOpenAfter() {
        return Duration.ofSeconds(30);
    }

    protected abstract <T> T fallback(ResilienceContext ctx, Throwable cause);

    @Override
    public <T> T execute(ResilienceContext ctx, ResilienceCallable<T> action) {
        long now = System.currentTimeMillis();
        if (state == State.OPEN) {
            if (now - openedAt < circuitBreakerHalfOpenAfter().toMillis()) {
                return fallback(ctx, new IllegalStateException("熔断已打开"));
            }
            state = State.HALF_OPEN;
        }

        Exception lastError = null;
        for (int attempt = 0; attempt <= maxRetries(); attempt++) {
            try {
                T result = executeWithTimeout(action);
                state = State.CLOSED;
                failureCount = 0;
                return result;
            } catch (Exception e) {
                lastError = e;
            }
        }

        failureCount++;
        if (failureCount >= circuitBreakerThreshold()) {
            state = State.OPEN;
            openedAt = now;
        }
        return fallback(ctx, lastError);
    }

    private <T> T executeWithTimeout(ResilienceCallable<T> action) throws Exception {
        Duration t = timeout();
        if (t == null || t.isZero() || t.isNegative()) {
            return action.call();
        }
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            try {
                return action.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        try {
            return future.get(t.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("执行超时: " + t);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }
}
