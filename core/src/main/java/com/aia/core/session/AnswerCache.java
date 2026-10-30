package com.aia.core.session;

import java.util.Optional;

/**
 * 热点问题答案缓存：同一个 agent 下，归一化后相同的问题直接命中已有答案，省掉检索与模型调用。
 */
public interface AnswerCache {

    Optional<String> get(String agentId, String question);

    void put(String agentId, String question, String answer);
}
