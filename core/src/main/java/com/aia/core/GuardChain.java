package com.aia.core;

import java.util.List;
import java.util.function.Supplier;

public final class GuardChain {

    private final List<Guard> guards;

    public GuardChain(List<Guard> guards) {
        this.guards = List.copyOf(guards);
    }

    public <T> T execute(GuardContext ctx, Supplier<T> action) {
        for (Guard guard : guards) {
            guard.before(ctx);
        }
        T result = null;
        Throwable error = null;
        try {
            result = action.get();
            return result;
        } catch (RuntimeException | Error e) {
            error = e;
            throw e;
        } finally {
            for (Guard guard : guards) {
                guard.after(ctx, result, error);
            }
        }
    }
}
