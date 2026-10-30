package io.github.dongcs9512.agentloom.core.session;

import io.github.dongcs9512.agentloom.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTest {

    @Test
    void newSessionStartsEmpty() {
        Session session = new Session("s1", "a1");

        assertEquals("s1", session.id());
        assertEquals("a1", session.agentId());
        assertTrue(session.messages().isEmpty());
        assertTrue(session.slots().isEmpty());
        assertNull(session.summary());
    }

    @Test
    void addMessageAppendsAndBumpsUpdatedAt() {
        Session session = new Session("s1", "a1");
        long before = session.updatedAt();

        session.addMessage(ChatMessage.user("你好"));

        assertEquals(1, session.messages().size());
        assertEquals("你好", session.messages().get(0).content());
        assertTrue(session.updatedAt() >= before);
    }

    @Test
    void slotsCanBeFilledAndRead() {
        Session session = new Session("s1", "a1");

        session.putSlot("incident", "被公司无故辞退");

        assertEquals("被公司无故辞退", session.slots().get("incident"));
        assertEquals("", session.slot("missing"));
    }

    @Test
    void summaryAndMessagesCanBeReplaced() {
        Session session = new Session("s1", "a1");
        session.addMessage(ChatMessage.user("旧消息"));

        session.replaceHistory("用户咨询劳动纠纷", List.of(ChatMessage.user("新消息")));

        assertEquals("用户咨询劳动纠纷", session.summary());
        assertEquals(1, session.messages().size());
        assertEquals("新消息", session.messages().get(0).content());
    }

    @Test
    void snapshotRoundTripsThroughConstructor() {
        Session original = new Session("s1", "a1");
        original.addMessage(ChatMessage.user("你好"));
        original.putSlot("when", "上周五");
        original.replaceHistory("摘要", original.messages());

        Session restored = new Session("s1", "a1", original.messages(), original.summary(),
                original.slots(), original.updatedAt());

        assertEquals("摘要", restored.summary());
        assertEquals(Map.of("when", "上周五"), restored.slots());
        assertEquals(1, restored.messages().size());
        assertNotNull(restored.updatedAt());
    }
}
