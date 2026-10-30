package io.github.dongcs9512.agentloom.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatChunkTest {

    @Test
    void singleArgConstructorDefaults() {
        ChatChunk c = new ChatChunk("hi");
        assertEquals("hi", c.content());
        assertTrue(c.toolCalls().isEmpty());
        assertNull(c.finishReason());
    }

    @Test
    void fullConstructorHoldsFields() {
        ChatChunk c = new ChatChunk(null, List.of(new ToolCall("c1", "f", Map.of())), "tool_calls");
        assertNull(c.content());
        assertEquals(1, c.toolCalls().size());
        assertEquals("tool_calls", c.finishReason());
    }
}
