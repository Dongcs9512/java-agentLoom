package io.github.dongcs9512.agentloom.core;

public interface ResiliencePolicy {
    <T> T execute(ResilienceContext ctx, ResilienceCallable<T> action);
}
