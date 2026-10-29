package com.aia.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbstractResiliencePolicyTest {

    static class TestPolicy extends AbstractResiliencePolicy {
        private final int retries;
        private final int threshold;
        private final Duration timeout;
        private final Object fallbackValue;

        TestPolicy(int retries, int threshold, Duration timeout, Object fallbackValue) {
            this.retries = retries;
            this.threshold = threshold;
            this.timeout = timeout;
            this.fallbackValue = fallbackValue;
        }

        @Override
        protected int maxRetries() {
            return retries;
        }

        @Override
        protected int circuitBreakerThreshold() {
            return threshold;
        }

        @Override
        protected Duration timeout() {
            return timeout;
        }

        @Override
        protected <T> T fallback(ResilienceContext ctx, Throwable cause) {
            return (T) fallbackValue;
        }
    }

    @Test
    void retriesThenSucceeds() {
        TestPolicy policy = new TestPolicy(2, 5, Duration.ofSeconds(5), "fb");
        AtomicInteger attempts = new AtomicInteger();
        String result = policy.execute(new ResilienceContext("x"), () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("fail");
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void fallsBackAfterExhaustion() {
        TestPolicy policy = new TestPolicy(2, 5, Duration.ofSeconds(5), "fb");
        String result = policy.execute(new ResilienceContext("x"), () -> {
            throw new RuntimeException("always");
        });
        assertEquals("fb", result);
    }

    @Test
    void circuitBreakerOpensAndSkipsAction() {
        TestPolicy policy = new TestPolicy(0, 2, Duration.ofSeconds(5), "fb");
        AtomicInteger attempts = new AtomicInteger();
        ResilienceContext ctx = new ResilienceContext("x");

        policy.execute(ctx, () -> {
            attempts.incrementAndGet();
            throw new RuntimeException("e");
        });
        policy.execute(ctx, () -> {
            attempts.incrementAndGet();
            throw new RuntimeException("e");
        });
        assertEquals(2, attempts.get());

        String result = policy.execute(ctx, () -> {
            attempts.incrementAndGet();
            return "should-not-run";
        });
        assertEquals("fb", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void timeoutTriggersFallback() {
        TestPolicy policy = new TestPolicy(0, 5, Duration.ofMillis(50), "fb");
        String result = policy.execute(new ResilienceContext("x"), () -> {
            Thread.sleep(200);
            return "late";
        });
        assertEquals("fb", result);
    }
}
