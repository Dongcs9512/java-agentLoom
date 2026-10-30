package com.aia.core.session;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 热缓存 + 归档存储双写。读优先热缓存，未命中再回源归档并回填；写热缓存同步、归档异步。
 */
public final class CompositeAnswerCache implements AnswerCache {

    private static final Logger LOG = Logger.getLogger(CompositeAnswerCache.class.getName());

    private final AnswerCache hot;
    private final AnswerCache archive;
    private final Executor archiveExecutor;

    public CompositeAnswerCache(AnswerCache hot, AnswerCache archive, Executor archiveExecutor) {
        this.hot = hot;
        this.archive = archive;
        this.archiveExecutor = archiveExecutor;
    }

    @Override
    public Optional<String> get(String agentId, String question) {
        Optional<String> cached = hot.get(agentId, question);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<String> archived = archive.get(agentId, question);
        archived.ifPresent(answer -> {
            try {
                hot.put(agentId, question, answer);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "答案缓存回填失败", e);
            }
        });
        return archived;
    }

    @Override
    public void put(String agentId, String question, String answer) {
        hot.put(agentId, question, answer);
        archiveExecutor.execute(() -> {
            try {
                archive.put(agentId, question, answer);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "答案归档写入失败", e);
            }
        });
    }
}
