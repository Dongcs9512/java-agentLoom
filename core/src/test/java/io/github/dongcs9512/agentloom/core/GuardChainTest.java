package io.github.dongcs9512.agentloom.core;

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

    @Test
    void earlierGuardIsReleasedWhenLaterGuardRejects() {
        ConcurrencyGuard first = new ConcurrencyGuard(1, 0);
        Guard rejecting = new Guard() {
            @Override
            public void before(GuardContext ctx) {
                throw new IllegalStateException("后面的闸门拒绝");
            }

            @Override
            public void after(GuardContext ctx, Object result, Throwable error) {
            }
        };
        GuardChain chain = new GuardChain(List.of(first, rejecting));

        assertThrows(IllegalStateException.class, () -> chain.execute(new GuardContext("k"), () -> "ok"));

        assertEquals(1, first.availablePermits(), "前面已占用的许可必须释放，否则会漏光");
    }
}
