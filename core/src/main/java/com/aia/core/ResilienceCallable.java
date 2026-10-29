package com.aia.core;

@FunctionalInterface
public interface ResilienceCallable<T> {
    T call() throws Exception;
}
