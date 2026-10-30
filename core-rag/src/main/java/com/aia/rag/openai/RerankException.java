package com.aia.rag.openai;

public class RerankException extends RuntimeException {

    private final int statusCode;

    public RerankException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
