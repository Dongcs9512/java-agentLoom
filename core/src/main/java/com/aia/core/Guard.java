package com.aia.core;

public interface Guard {
    void before(GuardContext ctx);

    void after(GuardContext ctx, Object result, Throwable error);
}
