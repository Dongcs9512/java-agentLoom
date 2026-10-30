package com.aia.core.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 关键词场景路由：每条规则关联一个专业 agent 和一组关键词，命中最多关键词的规则胜出。
 * 规则可来自配置文件或数据库，顺序即优先级（同分时排前面的赢）。
 */
public final class KeywordScenarioRouter implements ScenarioRouter {

    /**
     * @param weights 关键词权重，未配置的关键词按 1 计。
     *                用于让「社保」「工伤」这类具体领域词压过「辞退」这类泛化词。
     */
    public record Rule(String agentId, List<String> keywords, Map<String, Integer> weights) {
        public Rule {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            weights = weights == null ? Map.of() : Map.copyOf(weights);
        }

        public Rule(String agentId, List<String> keywords) {
            this(agentId, keywords, Map.of());
        }
    }

    private final List<Rule> rules;
    private final String defaultAgentId;
    private final int minScore;

    public KeywordScenarioRouter(List<Rule> rules, String defaultAgentId) {
        this(rules, defaultAgentId, 1);
    }

    /**
     * @param minScore 低于这个得分就认为没把握，交给默认 agent（级联时可传 null 默认让它落到下一个策略）
     */
    public KeywordScenarioRouter(List<Rule> rules, String defaultAgentId, int minScore) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        this.defaultAgentId = defaultAgentId == null || defaultAgentId.isBlank() ? null : defaultAgentId;
        this.minScore = Math.max(1, minScore);
    }

    @Override
    public Optional<String> route(String text, Session session) {
        StringBuilder haystack = new StringBuilder(text == null ? "" : text);
        if (session != null) {
            session.slots().forEach((name, value) -> haystack.append(' ').append(value));
        }
        String content = haystack.toString().toLowerCase(java.util.Locale.ROOT);
        if (content.isBlank()) {
            return Optional.ofNullable(defaultAgentId);
        }

        String bestAgent = null;
        int bestScore = 0;
        for (Rule rule : rules) {
            int score = 0;
            for (String keyword : rule.keywords()) {
                if (!keyword.isBlank() && content.contains(keyword.toLowerCase(java.util.Locale.ROOT))) {
                    score += rule.weights().getOrDefault(keyword, 1);
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestAgent = rule.agentId();
            }
        }
        if (bestAgent != null && bestScore >= minScore) {
            return Optional.of(bestAgent);
        }
        return Optional.ofNullable(defaultAgentId);
    }

    public List<Rule> rules() {
        return new ArrayList<>(rules);
    }
}
