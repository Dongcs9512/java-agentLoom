package com.aia.core;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 并发闸门：同时最多放行 N 个请求，超出的最多等一会儿，等不到就快速失败。
 *
 * <p>高并发下模型调用是最贵的资源，不做闸门会把线程、连接、配额一起打满；
 * 快速失败让前端能立刻提示"繁忙"，比全部超时拖死好得多。
 */
public final class ConcurrencyGuard implements Guard {

    private final Semaphore semaphore;
    private final long maxWaitMillis;
    private final String name;

    public ConcurrencyGuard(int maxConcurrent, long maxWaitMillis) {
        this("model", maxConcurrent, maxWaitMillis);
    }

    public ConcurrencyGuard(String name, int maxConcurrent, long maxWaitMillis) {
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("并发上限必须大于 0");
        }
        this.name = name;
        this.semaphore = new Semaphore(maxConcurrent, true);
        this.maxWaitMillis = Math.max(0, maxWaitMillis);
    }

    @Override
    public void before(GuardContext ctx) {
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(maxWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OverloadedException(name + " 并发排队被中断");
        }
        if (!acquired) {
            throw new OverloadedException(name + " 并发已满，请稍后重试");
        }
    }

    @Override
    public void after(GuardContext ctx, Object result, Throwable error) {
        semaphore.release();
    }

    public int availablePermits() {
        return semaphore.availablePermits();
    }
}
