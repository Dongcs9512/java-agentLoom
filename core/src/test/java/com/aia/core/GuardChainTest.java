package com.aia.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuardChainTest {

    @Test
    void guardBeforeAndAfterAreCalled() {
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();
        Guard g = new Guard() {
            @Override
            public void before(GuardContext ctx) {
                before.incrementAndGet();
            }

            @Override
            public void after(GuardContext ctx, Object result, Throwable error) {
                after.incrementAndGet();
            }
        };
        GuardChain chain = new GuardChain(List.of(g));
        String result = chain.execute(new GuardContext("k"), () -> "ok");
        assertEquals("ok", result);
        assertEquals(1, before.get());
        assertEquals(1, after.get());
    }

    @Test
    void rateLimitRejectsOverLimit() {
        RateLimitGuard guard = new RateLimitGuard(2);
        GuardChain chain = new GuardChain(List.of(guard));
        GuardContext ctx = new GuardContext("k");
        chain.execute(ctx, () -> "a");
        chain.execute(ctx, () -> "b");
        assertThrows(IllegalStateException.class, () -> chain.execute(ctx, () -> "c"));
    }
}
