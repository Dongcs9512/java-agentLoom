package com.aia.rag.redis;

public class RedisVectorStoreException extends RuntimeException {

    public RedisVectorStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
