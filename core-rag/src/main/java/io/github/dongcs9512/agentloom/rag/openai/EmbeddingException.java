package io.github.dongcs9512.agentloom.rag.openai;

public class EmbeddingException extends RuntimeException {

    private final int statusCode;

    public EmbeddingException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
