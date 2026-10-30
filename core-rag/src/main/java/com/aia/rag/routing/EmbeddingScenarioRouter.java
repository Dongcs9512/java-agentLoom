package com.aia.rag.routing;

import com.aia.core.session.ScenarioRouter;
import com.aia.core.session.Session;
import com.aia.rag.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 向量语义路由：每个专业方向给几条示例话术，查询向量和示例向量取最大余弦相似度，
 * 最高者且超过阈值才算命中。比关键词抗改写（"薪水""报酬""工资"能归到一起），
 * 代价是每次路由要多花一次向量化调用。
 */
public final class EmbeddingScenarioRouter implements ScenarioRouter {

    public record Rule(String agentId, List<String> examples) {
        public Rule {
            examples = examples == null ? List.of() : List.copyOf(examples);
        }
    }

    private final EmbeddingModel embedder;
    private final List<Rule> rules;
    private final double minSimilarity;
    private final String defaultAgentId;
    /** 示例向量在首次使用时才计算，避免启动阶段就去调向量接口。 */
    private volatile List<List<float[]>> exampleVectors;

    public EmbeddingScenarioRouter(EmbeddingModel embedder, List<Rule> rules, double minSimilarity,
                                   String defaultAgentId) {
        this.embedder = embedder;
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        this.minSimilarity = minSimilarity;
        this.defaultAgentId = defaultAgentId == null || defaultAgentId.isBlank() ? null : defaultAgentId;
    }

    private List<List<float[]>> vectors() {
        List<List<float[]>> cached = exampleVectors;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (exampleVectors == null) {
                List<List<float[]>> vectors = new ArrayList<>();
                for (Rule rule : rules) {
                    List<float[]> ruleVectors = new ArrayList<>();
                    for (String example : rule.examples()) {
                        ruleVectors.add(embedder.embed(example));
                    }
                    vectors.add(ruleVectors);
                }
                exampleVectors = List.copyOf(vectors);
            }
            return exampleVectors;
        }
    }

    @Override
    public Optional<String> route(String text, Session session) {
        StringBuilder content = new StringBuilder(text == null ? "" : text);
        if (session != null) {
            session.slots().forEach((name, value) -> {
                if (!name.startsWith("__")) {
                    content.append(' ').append(value);
                }
            });
        }
        if (content.toString().isBlank()) {
            return Optional.ofNullable(defaultAgentId);
        }

        float[] query;
        List<List<float[]>> vectors;
        try {
            vectors = vectors();
            query = embedder.embed(content.toString());
        } catch (RuntimeException e) {
            // 向量服务不可用时降级，交给后面的策略或默认 agent
            return Optional.ofNullable(defaultAgentId);
        }

        String bestAgent = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < rules.size() && i < vectors.size(); i++) {
            for (float[] example : vectors.get(i)) {
                double score = cosine(query, example);
                if (score > bestScore) {
                    bestScore = score;
                    bestAgent = rules.get(i).agentId();
                }
            }
        }
        if (bestAgent != null && bestScore >= minSimilarity) {
            return Optional.of(bestAgent);
        }
        return Optional.ofNullable(defaultAgentId);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
