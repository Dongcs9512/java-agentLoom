package com.aia.llm.openai;

public class ChatModelException extends RuntimeException {

    private final int statusCode;

    public ChatModelException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
