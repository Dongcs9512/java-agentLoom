package com.aia.session.jdbc;

import com.aia.core.session.Session;
import com.aia.llm.ChatMessage;
import com.aia.llm.ToolCall;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcSessionStoreTest {

    private JdbcSessionStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sessdb;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        store = new JdbcSessionStore(ds);
    }

    private Session sample() {
        Session session = new Session("s1", "a1");
        session.addMessage(ChatMessage.user("我要咨询劳动纠纷"));
        session.addMessage(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("c1", "search_knowledge", Map.of("query", "辞退")))));
        session.addMessage(ChatMessage.tool("c1", "检索结果"));
        session.putSlot("incident", "被辞退");
        session.replaceHistory("用户咨询劳动纠纷", session.messages());
        return session;
    }

    @Test
    void roundTripsMessagesSlotsAndSummary() {
        store.save(sample());

        Session loaded = store.load("s1").orElseThrow();

        assertEquals("a1", loaded.agentId());
        assertEquals("用户咨询劳动纠纷", loaded.summary());
        assertEquals("被辞退", loaded.slot("incident"));
        assertEquals(3, loaded.messages().size());
        assertEquals(1, loaded.messages().get(1).toolCalls().size());
        assertEquals("c1", loaded.messages().get(2).toolCallId());
    }

    @Test
    void savingTwiceReplacesRatherThanDuplicates() {
        store.save(sample());
        store.save(sample());

        assertEquals(3, store.load("s1").orElseThrow().messages().size());
    }

    @Test
    void loadMissingReturnsEmpty() {
        assertTrue(store.load("nope").isEmpty());
    }

    @Test
    void deleteRemovesSessionAndMessages() {
        store.save(sample());

        store.delete("s1");

        assertTrue(store.load("s1").isEmpty());
    }
}
