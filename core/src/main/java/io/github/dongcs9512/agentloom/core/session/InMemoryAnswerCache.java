package io.github.dongcs9512.agentloom.core.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 有容量上限的 LRU 内存缓存，超出容量时淘汰最久未使用的条目。
 */
public final class InMemoryAnswerCache implements AnswerCache {

    private final int capacity;
    private final Map<String, String> entries;

    public InMemoryAnswerCache(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > InMemoryAnswerCache.this.capacity;
            }
        };
    }

    @Override
    public synchronized Optional<String> get(String agentId, String question) {
        return Optional.ofNullable(entries.get(key(agentId, question)));
    }

    @Override
    public synchronized void put(String agentId, String question, String answer) {
        String normalized = QuestionNormalizer.normalize(question);
        if (normalized.isEmpty() || answer == null || answer.isBlank()) {
            return;
        }
        entries.put(key(agentId, question), answer);
    }

    private static String key(String agentId, String question) {
        return (agentId == null ? "" : agentId) + "|" + QuestionNormalizer.normalize(question);
    }
}
