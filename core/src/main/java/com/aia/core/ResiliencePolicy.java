package com.aia.core;

public interface ResiliencePolicy {
    <T> T execute(ResilienceContext ctx, ResilienceCallable<T> action);
}
