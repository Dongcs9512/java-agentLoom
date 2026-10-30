package com.aia.core.session;

import com.aia.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeSessionStoreTest {

    private final InMemorySessionStore hot = new InMemorySessionStore();
    private final InMemorySessionStore archive = new InMemorySessionStore();
    private final Executor direct = Runnable::run;

    private CompositeSessionStore store() {
        return new CompositeSessionStore(hot, archive, direct);
    }

    @Test
    void saveWritesBothStores() {
        Session session = new Session("s1", "a1");
        session.addMessage(ChatMessage.user("你好"));

        store().save(session);

        assertEquals(1, hot.load("s1").orElseThrow().messages().size());
        assertEquals(1, archive.load("s1").orElseThrow().messages().size());
    }

    @Test
    void loadFallsBackToArchiveAndWarmsHotStore() {
        Session session = new Session("s1", "a1");
        session.addMessage(ChatMessage.user("归档里才有"));
        archive.save(session);

        Optional<Session> loaded = store().load("s1");

        assertTrue(loaded.isPresent());
        assertEquals("归档里才有", loaded.get().messages().get(0).content());
        assertTrue(hot.load("s1").isPresent(), "回源后应回填热存储");
    }

    @Test
    void loadPrefersHotStore() {
        Session hotSession = new Session("s1", "a1");
        hotSession.addMessage(ChatMessage.user("热数据"));
        hot.save(hotSession);
        Session archived = new Session("s1", "a1");
        archived.addMessage(ChatMessage.user("冷数据"));
        archive.save(archived);

        assertEquals("热数据", store().load("s1").orElseThrow().messages().get(0).content());
    }

    @Test
    void deleteRemovesFromBoth() {
        Session session = new Session("s1", "a1");
        store().save(session);

        store().delete("s1");

        assertTrue(hot.load("s1").isEmpty());
        assertTrue(archive.load("s1").isEmpty());
    }

    @Test
    void archiveFailureDoesNotBreakSave() {
        SessionStore brokenArchive = new SessionStore() {
            @Override
            public void save(Session session) {
                throw new IllegalStateException("数据库不可用");
            }

            @Override
            public Optional<Session> load(String sessionId) {
                return Optional.empty();
            }

            @Override
            public void delete(String sessionId) {
            }
        };
        Session session = new Session("s1", "a1");

        new CompositeSessionStore(hot, brokenArchive, direct).save(session);

        assertTrue(hot.load("s1").isPresent(), "归档失败不应影响热存储写入");
    }
}
