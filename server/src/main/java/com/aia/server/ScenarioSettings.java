package com.aia.server;

import com.aia.core.session.ScenarioRouter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景规则配置，形如：
 * <pre>
 * aia:
 *   scenarios:
 *     enabled: true
 *     default-agent: legal-advisor
 *     rules:
 *       - agent: legal-labor
 *         keywords: 辞退,欠薪,加班费
 * </pre>
 */
public final class ScenarioSettings {

    public record Rule(String agent, List<String> keywords, Map<String, Integer> weights,
                       String description, List<String> examples) {
        public Rule(String agent, List<String> keywords) {
            this(agent, keywords, Map.of(), "", List.of());
        }
    }

    private final boolean enabled;
    private final String strategy;
    private final int minScore;
    private final String defaultAgent;
    private final List<Rule> rules;

    private ScenarioSettings(boolean enabled, String strategy, int minScore, String defaultAgent, List<Rule> rules) {
        this.enabled = enabled;
        this.strategy = strategy;
        this.minScore = minScore;
        this.defaultAgent = defaultAgent;
        this.rules = rules;
    }

    public static ScenarioSettings from(Environment env) {
        boolean enabled = Boolean.parseBoolean(env.getProperty("aia.scenarios.enabled", "false"));
        String strategy = env.getProperty("aia.scenarios.strategy", "keyword");
        int minScore = Integer.parseInt(env.getProperty("aia.scenarios.min-score", "3"));
        String defaultAgent = env.getProperty("aia.scenarios.default-agent", "");
        List<Rule> rules = new ArrayList<>();
        int index = 0;
        while (true) {
            String agent = env.getProperty("aia.scenarios.rules[" + index + "].agent");
            if (agent == null) {
                break;
            }
            String keywords = env.getProperty("aia.scenarios.rules[" + index + "].keywords", "");
            String description = env.getProperty("aia.scenarios.rules[" + index + "].description", "");
            String exampleText = env.getProperty("aia.scenarios.rules[" + index + "].examples", "");
            List<String> parsed = new ArrayList<>();
            Map<String, Integer> weights = new LinkedHashMap<>();
            for (String token : keywords.split(",")) {
                String item = token.trim();
                if (item.isEmpty()) {
                    continue;
                }
                int star = item.lastIndexOf('*');
                if (star > 0) {
                    String keyword = item.substring(0, star).trim();
                    try {
                        weights.put(keyword, Integer.parseInt(item.substring(star + 1).trim()));
                        parsed.add(keyword);
                        continue;
                    } catch (NumberFormatException ignored) {
                        // 权重写错就按普通关键词处理
                    }
                }
                parsed.add(item);
            }
            List<String> examples = new ArrayList<>();
            for (String token : exampleText.split(",")) {
                if (!token.trim().isEmpty()) {
                    examples.add(token.trim());
                }
            }
            rules.add(new Rule(agent, parsed, weights, description.trim(), examples));
            index++;
        }
        return new ScenarioSettings(enabled, strategy, minScore, defaultAgent, rules);
    }

    public boolean enabled() {
        return enabled;
    }

    public String strategy() {
        return strategy;
    }

    public int minScore() {
        return minScore;
    }

    public String defaultAgent() {
        return defaultAgent;
    }

    public List<Rule> rules() {
        return rules;
    }

    /**
     * 每个专业方向的描述，向量与 LLM 分类都用它，没配则退回关键词拼接。
     */
    public String descriptionOf(Rule rule) {
        return rule.description() == null || rule.description().isBlank()
                ? String.join("、", rule.keywords())
                : rule.description();
    }

    public List<String> examplesOf(Rule rule) {
        return rule.examples().isEmpty() ? List.of(descriptionOf(rule)) : rule.examples();
    }

    public ScenarioRouter toRouter() {
        return new com.aia.core.session.KeywordScenarioRouter(
                rules.stream()
                        .map(r -> new com.aia.core.session.KeywordScenarioRouter.Rule(
                                r.agent(), r.keywords(), r.weights()))
                        .toList(),
                defaultAgent);
    }

    /**
     * 高置信快通道：只有得分够高才短路，否则交给后面的策略（级联时用）。
     */
    public ScenarioRouter toStrictKeywordRouter() {
        return new com.aia.core.session.KeywordScenarioRouter(
                rules.stream()
                        .map(r -> new com.aia.core.session.KeywordScenarioRouter.Rule(
                                r.agent(), r.keywords(), r.weights()))
                        .toList(),
                null, minScore);
    }

    /**
     * 场景路由的 Bean 装配辅助：未启用时返回 null。
     */
    public static ScenarioRouter resolve(ObjectProvider<ScenarioSettings> settings) {
        ScenarioSettings s = settings.getIfAvailable();
        return s == null || !s.enabled() ? null : s.toRouter();
    }
}
