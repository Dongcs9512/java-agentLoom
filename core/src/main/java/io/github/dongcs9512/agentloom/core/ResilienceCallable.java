package io.github.dongcs9512.agentloom.core;

@FunctionalInterface
public interface ResilienceCallable<T> {
    T call() throws Exception;
}
