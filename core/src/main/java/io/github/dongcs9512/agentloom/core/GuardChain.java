package io.github.dongcs9512.agentloom.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class GuardChain {

    private final List<Guard> guards;

    public GuardChain(List<Guard> guards) {
        this.guards = List.copyOf(guards);
    }

    public <T> T execute(GuardContext ctx, Supplier<T> action) {
        List<Guard> entered = new ArrayList<>(guards.size());
        T result = null;
        Throwable error = null;
        try {
            for (Guard guard : guards) {
                guard.before(ctx);
                // 只有成功进入的 guard 才需要 after；后面的 before 抛异常时，
                // 前面已占用的信号量/计数必须在这里释放，否则会持续泄漏
                entered.add(guard);
            }
            result = action.get();
            return result;
        } catch (RuntimeException | Error e) {
            error = e;
            throw e;
        } finally {
            for (int i = entered.size() - 1; i >= 0; i--) {
                entered.get(i).after(ctx, result, error);
            }
        }
    }
}
