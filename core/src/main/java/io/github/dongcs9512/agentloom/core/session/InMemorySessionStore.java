package io.github.dongcs9512.agentloom.core.session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现，进程内有效。存取都做深拷贝，避免调用方改到已保存的会话。
 */
public final class InMemorySessionStore implements SessionStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(Session session) {
        sessions.put(session.id(), session.copy());
    }

    @Override
    public Optional<Session> load(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId)).map(Session::copy);
    }

    @Override
    public void delete(String sessionId) {
        sessions.remove(sessionId);
    }
}
