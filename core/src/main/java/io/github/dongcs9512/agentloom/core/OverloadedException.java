package io.github.dongcs9512.agentloom.core;

/**
 * 系统过载：并发已满或触发限流。上层应映射成 503/429 并提示稍后重试，
 * 而不是当成 500 内部错误。
 */
public class OverloadedException extends IllegalStateException {

    public OverloadedException(String message) {
        super(message);
    }
}
