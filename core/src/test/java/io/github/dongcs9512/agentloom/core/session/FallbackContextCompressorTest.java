package io.github.dongcs9512.agentloom.core.session;

import io.github.dongcs9512.agentloom.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FallbackContextCompressorTest {

    @Test
    void usesPrimaryResultWhenItWorks() {
        ContextCompressor primary = (summary, messages) -> new CompressedContext("主策略", List.of());
        ContextCompressor fallback = (summary, messages) -> new CompressedContext("兜底", List.of());

        CompressedContext result = new FallbackContextCompressor(primary, fallback)
                .compress(null, List.of(ChatMessage.user("x")));

        assertEquals("主策略", result.summary());
    }

    @Test
    void fallsBackWhenPrimaryThrows() {
        ContextCompressor primary = (summary, messages) -> {
            throw new IllegalStateException("主策略不可用");
        };
        ContextCompressor fallback = (summary, messages) -> new CompressedContext("兜底", List.of());

        CompressedContext result = new FallbackContextCompressor(primary, fallback)
                .compress(null, List.of(ChatMessage.user("x")));

        assertEquals("兜底", result.summary());
    }
}
