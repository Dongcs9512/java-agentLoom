package com.aia.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleFlightTest {

    @Test
    void concurrentSameKeyRunsActionOnce() throws Exception {
        SingleFlight<String, String> flight = new SingleFlight<>(5000);
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return flight.execute("same-question", () -> {
                        invocations.incrementAndGet();
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "answer";
                    });
                }));
            }
            start.countDown();
            for (var future : futures) {
                assertEquals("answer", future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, invocations.get(), "同一问题并发时只应真正执行一次");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentKeysRunSeparately() {
        SingleFlight<String, Integer> flight = new SingleFlight<>(1000);
        AtomicInteger invocations = new AtomicInteger();

        flight.execute("a", invocations::incrementAndGet);
        flight.execute("b", invocations::incrementAndGet);

        assertEquals(2, invocations.get());
    }

    @Test
    void failurePropagatesToLeaderAndWaiters() throws Exception {
        SingleFlight<String, String> flight = new SingleFlight<>(2000);
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var leader = pool.submit(() -> flight.execute("k", () -> {
                started.countDown();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("模型挂了");
            }));
            started.await();

            assertThrows(IllegalStateException.class,
                    () -> flight.execute("k", () -> "不该执行"));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> leader.get(2, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void entryIsRemovedAfterCompletion() {
        SingleFlight<String, String> flight = new SingleFlight<>(1000);
        flight.execute("k", () -> "v");

        assertEquals(0, flight.inFlightCount());
    }
}
