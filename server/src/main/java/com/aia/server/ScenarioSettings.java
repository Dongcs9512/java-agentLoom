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

    public record Rule(String agent, List<String> keywords, Map<String, Integer> weights) {
        public Rule(String agent, List<String> keywords) {
            this(agent, keywords, Map.of());
        }
    }

    private final boolean enabled;
    private final String defaultAgent;
    private final List<Rule> rules;

    private ScenarioSettings(boolean enabled, String defaultAgent, List<Rule> rules) {
        this.enabled = enabled;
        this.defaultAgent = defaultAgent;
        this.rules = rules;
    }

    public static ScenarioSettings from(Environment env) {
        boolean enabled = Boolean.parseBoolean(env.getProperty("aia.scenarios.enabled", "false"));
        String defaultAgent = env.getProperty("aia.scenarios.default-agent", "");
        List<Rule> rules = new ArrayList<>();
        int index = 0;
        while (true) {
            String agent = env.getProperty("aia.scenarios.rules[" + index + "].agent");
            if (agent == null) {
                break;
            }
            String keywords = env.getProperty("aia.scenarios.rules[" + index + "].keywords", "");
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
            rules.add(new Rule(agent, parsed, weights));
            index++;
        }
        return new ScenarioSettings(enabled, defaultAgent, rules);
    }

    public boolean enabled() {
        return enabled;
    }

    public String defaultAgent() {
        return defaultAgent;
    }

    public List<Rule> rules() {
        return rules;
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
     * 场景路由的 Bean 装配辅助：未启用时返回 null。
     */
    public static ScenarioRouter resolve(ObjectProvider<ScenarioSettings> settings) {
        ScenarioSettings s = settings.getIfAvailable();
        return s == null || !s.enabled() ? null : s.toRouter();
    }
}
