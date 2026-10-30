package com.aia.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyGuardTest {

    @Test
    void rejectsWhenSaturated() {
        ConcurrencyGuard guard = new ConcurrencyGuard(1, 0);
        GuardContext ctx = new GuardContext("k");
        guard.before(ctx);

        assertThrows(IllegalStateException.class, () -> guard.before(new GuardContext("k2")));

        guard.after(ctx, null, null);
        assertEquals(1, guard.availablePermits());
    }

    @Test
    void releaseHappensEvenWhenActionThrows() {
        ConcurrencyGuard guard = new ConcurrencyGuard(1, 0);
        GuardChain chain = new GuardChain(List.of(guard));

        assertThrows(IllegalStateException.class,
                () -> chain.execute(new GuardContext("k"), () -> {
                    throw new IllegalStateException("业务失败");
                }));

        assertEquals(1, guard.availablePermits(), "业务异常也必须归还许可");
    }

    @Test
    void allowsPermitsToBeReusedAfterRelease() {
        ConcurrencyGuard guard = new ConcurrencyGuard(2, 0);
        GuardContext ctx = new GuardContext("k");

        guard.before(ctx);
        guard.before(ctx);
        guard.after(ctx, null, null);

        assertEquals(1, guard.availablePermits());
        guard.before(ctx);
        assertEquals(0, guard.availablePermits());
    }

    @Test
    void waiterCanGetInWhenPermitFreesUp() throws Exception {
        ConcurrencyGuard guard = new ConcurrencyGuard(1, 2000);
        GuardContext ctx = new GuardContext("k");
        guard.before(ctx);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> {
                guard.before(new GuardContext("k2"));
                guard.after(new GuardContext("k2"), null, null);
                return "done";
            });
            Thread.sleep(100);
            guard.after(ctx, null, null);

            assertEquals("done", future.get(2, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
