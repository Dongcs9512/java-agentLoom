package io.github.dongcs9512.agentloom.core;

public interface Guard {
    void before(GuardContext ctx);

    void after(GuardContext ctx, Object result, Throwable error);
}
