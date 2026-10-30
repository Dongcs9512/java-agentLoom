package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.session.CascadingScenarioRouter;
import io.github.dongcs9512.agentloom.core.session.KeywordScenarioRouter;
import io.github.dongcs9512.agentloom.core.session.LlmScenarioRouter;
import io.github.dongcs9512.agentloom.core.session.ScenarioRouter;
import io.github.dongcs9512.agentloom.rag.EmbeddingModel;
import io.github.dongcs9512.agentloom.rag.routing.EmbeddingScenarioRouter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * 按 {@code aia.scenarios.strategy} 组装路由策略：
 * keyword（0 成本）/ embedding（一次向量化）/ llm（一次模型调用）/ cascade（快通道 + 逐级兜底）。
 */
@Component
public class ScenarioRouterFactory {

    private static final Logger LOG = Logger.getLogger(ScenarioRouterFactory.class.getName());

    /**
     * @param examples 向量路由用的示例话术（留空则退回 description）
     */
    public record Category(String agent, String description, List<String> examples) {
        public Category {
            examples = examples == null ? List.of() : List.copyOf(examples);
        }
    }

    private final ScenarioSettings settings;
    private final ObjectProvider<EmbeddingModel> embeddingModel;
    private final ChatModelFactory chatModelFactory;

    @Value("${agentloom.scenarios.llm-model:deepseek-chat}")
    private String llmModel;

    @Value("${agentloom.scenarios.embedding-min-similarity:0.62}")
    private double embeddingMinSimilarity;

    public ScenarioRouterFactory(ScenarioSettings settings, ObjectProvider<EmbeddingModel> embeddingModel,
                                 ChatModelFactory chatModelFactory) {
        this.settings = settings;
        this.embeddingModel = embeddingModel;
        this.chatModelFactory = chatModelFactory;
    }

    public ScenarioRouter create() {
        return create(settings.strategy(), categoriesFromSettings(), settings.defaultAgent());
    }

    public ScenarioRouter create(String strategy, List<Category> categories, String defaultAgent) {
        String normalized = strategy == null ? "keyword" : strategy.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "embedding" -> embedding(categories, defaultAgent);
            case "llm" -> llm(categories, defaultAgent);
            case "cascade" -> {
                List<ScenarioRouter> chain = new ArrayList<>();
                chain.add(settings.toStrictKeywordRouter());
                ScenarioRouter embeddingRouter = embeddingOrNull(categories, defaultAgent);
                if (embeddingRouter != null) {
                    chain.add(embeddingRouter);
                }
                ScenarioRouter llmRouter = llmOrNull(categories, defaultAgent);
                if (llmRouter != null) {
                    chain.add(llmRouter);
                }
                yield new CascadingScenarioRouter(chain);
            }
            default -> new KeywordScenarioRouter(keywordRules(), defaultAgent);
        };
    }

    private List<KeywordScenarioRouter.Rule> keywordRules() {
        return settings.rules().stream()
                .map(r -> new KeywordScenarioRouter.Rule(r.agent(), r.keywords(), r.weights()))
                .toList();
    }

    private ScenarioRouter embedding(List<Category> categories, String defaultAgent) {
        ScenarioRouter router = embeddingOrNull(categories, defaultAgent);
        return router != null ? router : settings.toRouter();
    }

    private ScenarioRouter embeddingOrNull(List<Category> categories, String defaultAgent) {
        EmbeddingModel embedder = embeddingModel.getIfAvailable();
        if (embedder == null) {
            LOG.warning("未启用向量化模型（aia.rag.enabled=false），向量路由不可用");
            return null;
        }
        return new EmbeddingScenarioRouter(embedder,
                categories.stream()
                        .map(c -> new EmbeddingScenarioRouter.Rule(c.agent(),
                                c.examples().isEmpty() ? List.of(c.description()) : c.examples()))
                        .toList(),
                embeddingMinSimilarity, defaultAgent);
    }

    private ScenarioRouter llm(List<Category> categories, String defaultAgent) {
        ScenarioRouter router = llmOrNull(categories, defaultAgent);
        return router != null ? router : settings.toRouter();
    }

    private ScenarioRouter llmOrNull(List<Category> categories, String defaultAgent) {
        try {
            return new LlmScenarioRouter(chatModelFactory.create(llmModel),
                    categories.stream()
                            .map(c -> new LlmScenarioRouter.Category(c.agent(), c.description()))
                            .toList(),
                    defaultAgent);
        } catch (RuntimeException e) {
            LOG.warning("LLM 路由不可用: " + e.getMessage());
            return null;
        }
    }

    private List<Category> categoriesFromSettings() {
        return settings.rules().stream()
                .map(r -> new Category(r.agent(), settings.descriptionOf(r), settings.examplesOf(r)))
                .toList();
    }
}
