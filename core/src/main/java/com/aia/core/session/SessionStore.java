package com.aia.core.session;

import java.util.Optional;

public interface SessionStore {

    void save(Session session);

    Optional<Session> load(String sessionId);

    void delete(String sessionId);
}
