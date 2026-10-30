package io.github.dongcs9512.agentloom.core.session;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 热存储 + 归档存储双写：读优先热存储，缺失时回源归档并回填；
 * 写同步写热存储、异步写归档。归档写失败只记日志，不影响主流程。
 */
public final class CompositeSessionStore implements SessionStore {

    private static final Logger LOG = Logger.getLogger(CompositeSessionStore.class.getName());

    private final SessionStore hot;
    private final SessionStore archive;
    private final Executor archiveExecutor;

    public CompositeSessionStore(SessionStore hot, SessionStore archive, Executor archiveExecutor) {
        this.hot = hot;
        this.archive = archive;
        this.archiveExecutor = archiveExecutor;
    }

    @Override
    public void save(Session session) {
        hot.save(session);
        archiveExecutor.execute(() -> {
            try {
                archive.save(session);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "会话归档写入失败: " + session.id(), e);
            }
        });
    }

    @Override
    public Optional<Session> load(String sessionId) {
        Optional<Session> cached = hot.load(sessionId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<Session> archived = archive.load(sessionId);
        archived.ifPresent(session -> {
            try {
                hot.save(session);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "会话回填热存储失败: " + sessionId, e);
            }
        });
        return archived;
    }

    @Override
    public void delete(String sessionId) {
        hot.delete(sessionId);
        archiveExecutor.execute(() -> {
            try {
                archive.delete(sessionId);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "会话归档删除失败: " + sessionId, e);
            }
        });
    }
}
