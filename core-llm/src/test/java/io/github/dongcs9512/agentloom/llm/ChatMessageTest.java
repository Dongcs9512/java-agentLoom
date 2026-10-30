package io.github.dongcs9512.agentloom.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageTest {

    @Test
    void systemFactorySetsRoleAndContent() {
        ChatMessage m = ChatMessage.system("x");
        assertEquals(ChatMessageRole.SYSTEM, m.role());
        assertEquals("x", m.content());
        assertTrue(m.toolCalls().isEmpty());
        assertNull(m.toolCallId());
    }

    @Test
    void assistantWithToolCallsKeepsCalls() {
        ChatMessage m = ChatMessage.assistantWithToolCalls(List.of(new ToolCall("c1", "f", Map.of("k", 1))));
        assertEquals(ChatMessageRole.ASSISTANT, m.role());
        assertEquals(1, m.toolCalls().size());
        assertNull(m.content());
    }

    @Test
    void toolFactorySetsToolCallId() {
        ChatMessage m = ChatMessage.tool("c1", "res");
        assertEquals(ChatMessageRole.TOOL, m.role());
        assertEquals("c1", m.toolCallId());
        assertEquals("res", m.content());
    }
}
