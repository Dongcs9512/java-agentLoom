package io.github.dongcs9512.agentloom.core;

import java.util.ArrayDeque;
import java.util.Deque;

public final class RateLimitGuard implements Guard {

    private final int maxPerSecond;
    private final Deque<Long> timestamps = new ArrayDeque<>();

    public RateLimitGuard(int maxPerSecond) {
        if (maxPerSecond <= 0) {
            throw new IllegalArgumentException("限流阈值必须大于 0");
        }
        this.maxPerSecond = maxPerSecond;
    }

    @Override
    public void before(GuardContext ctx) {
        long now = System.currentTimeMillis();
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= 1000L) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxPerSecond) {
                throw new OverloadedException("触发限流: " + maxPerSecond + "/s");
            }
            timestamps.addLast(now);
        }
    }

    @Override
    public void after(GuardContext ctx, Object result, Throwable error) {
        // 无需清理，before 里已滑动窗口
    }
}
