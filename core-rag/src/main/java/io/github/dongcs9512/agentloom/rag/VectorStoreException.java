package io.github.dongcs9512.agentloom.rag;

public class VectorStoreException extends RuntimeException {

    private final int statusCode;

    public VectorStoreException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
