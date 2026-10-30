package io.github.dongcs9512.agentloom.core.session;

import io.github.dongcs9512.agentloom.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InMemorySessionStoreTest {

    @Test
    void saveThenLoadReturnsContent() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = new Session("s1", "a1");
        session.addMessage(ChatMessage.user("你好"));

        store.save(session);
        Session loaded = store.load("s1").orElseThrow();

        assertEquals(1, loaded.messages().size());
    }

    @Test
    void loadMissingReturnsEmpty() {
        assertEquals(true, new InMemorySessionStore().load("nope").isEmpty());
    }

    @Test
    void loadingIsIsolatedFromLaterMutation() {
        InMemorySessionStore store = new InMemorySessionStore();
        Session session = new Session("s1", "a1");
        session.addMessage(ChatMessage.user("原始"));
        store.save(session);

        Session loaded = store.load("s1").orElseThrow();
        loaded.addMessage(ChatMessage.user("只改副本"));
        session.addMessage(ChatMessage.user("只改原对象"));

        Session reloaded = store.load("s1").orElseThrow();
        assertEquals(1, reloaded.messages().size());
        assertEquals("原始", reloaded.messages().get(0).content());
    }

    @Test
    void deleteRemoves() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.save(new Session("s1", "a1"));

        store.delete("s1");

        assertNull(store.load("s1").orElse(null));
    }
}
