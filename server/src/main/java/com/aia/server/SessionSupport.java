package com.aia.server;

import com.aia.core.session.AnswerCache;
import com.aia.core.session.ContextCompressor;
import com.aia.core.session.RetrievalRouter;
import com.aia.core.session.SessionStore;

/**
 * 会话能力装配：为 null 的项表示该能力关闭。
 *
 * @param store      会话存储，关闭则退化为单轮问答
 * @param cache      热点问题答案缓存，关闭则每次都走完整流程
 * @param router     检索路由，关闭则一律走知识库
 * @param compressor 上下文压缩，关闭则历史原样发送
 */
public record SessionSupport(
        SessionStore store,
        AnswerCache cache,
        RetrievalRouter router,
        ContextCompressor compressor) {

    public static SessionSupport disabled() {
        return new SessionSupport(null, null, null, null);
    }

    public boolean hasSession() {
        return store != null;
    }

    public boolean hasCache() {
        return cache != null;
    }

    public boolean hasRouter() {
        return router != null;
    }

    public boolean hasCompressor() {
        return compressor != null;
    }
}
