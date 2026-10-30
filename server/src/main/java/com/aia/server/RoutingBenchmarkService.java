package com.aia.server;

import com.aia.core.session.ScenarioRouter;
import com.aia.rag.eval.RoutingCase;
import com.aia.rag.eval.RoutingCaseResult;
import com.aia.rag.eval.RoutingDataset;
import com.aia.rag.eval.RoutingStrategyReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 同一批标注问题，分别用关键词 / 向量语义 / LLM 分类 / 级联 四种策略跑一遍，
 * 输出准确率与延迟，用来决定生产上用哪种。
 */
@Service
@ConditionalOnProperty(prefix = "aia.eval", name = "enabled", havingValue = "true")
public class RoutingBenchmarkService {

    private final ScenarioRouterFactory routerFactory;

    @Value("${aia.eval.dataset-dir:evals}")
    private String datasetDir;

    public RoutingBenchmarkService(ScenarioRouterFactory routerFactory) {
        this.routerFactory = routerFactory;
    }

    public RoutingDataset load(String datasetName) {
        String file = datasetName.endsWith(".json") ? datasetName : datasetName + ".json";
        return RoutingDataset.load(Path.of(datasetDir, file));
    }

    /**
     * @param llmModel LLM 分类用的模型名，null 时跳过该策略
     */
    public Map<String, RoutingStrategyReport> run(String datasetName, String llmModel) {
        RoutingDataset dataset = load(datasetName);
        List<ScenarioRouterFactory.Category> categories = dataset.scenarios().stream()
                .map(s -> new ScenarioRouterFactory.Category(s.agent(), s.description(), s.examples()))
                .toList();

        Map<String, RoutingStrategyReport> reports = new LinkedHashMap<>();
        for (String strategy : List.of("keyword", "embedding", "llm", "cascade")) {
            if ("llm".equals(strategy) && (llmModel == null || llmModel.isBlank())) {
                continue;
            }
            reports.put(strategy, benchmark(strategy,
                    routerFactory.create(strategy, categories, null), dataset));
        }
        return reports;
    }

    private RoutingStrategyReport benchmark(String name, ScenarioRouter router, RoutingDataset dataset) {
        List<RoutingCaseResult> results = new ArrayList<>();
        for (RoutingCase routingCase : dataset.cases()) {
            long start = System.nanoTime();
            String actual;
            try {
                actual = router.route(routingCase.query(), null).orElse(null);
            } catch (RuntimeException e) {
                actual = null;
            }
            long latencyMs = Math.round((System.nanoTime() - start) / 1_000_000.0);
            results.add(new RoutingCaseResult(routingCase.query(), routingCase.expected(), actual,
                    judge(routingCase.expected(), routingCase.alsoAcceptable(), actual), latencyMs));
        }
        return RoutingStrategyReport.of(name, results);
    }

    static boolean judge(String expected, List<String> alsoAcceptable, String actual) {
        if (expected == null) {
            return actual == null;
        }
        if (expected.equals(actual)) {
            return true;
        }
        return actual != null && alsoAcceptable.contains(actual);
    }

    public static List<RoutingStrategyReport> ranked(Map<String, RoutingStrategyReport> reports) {
        return RoutingStrategyReport.sortedByAccuracyThenSpeed(new ArrayList<>(reports.values()));
    }

    public static Optional<RoutingStrategyReport> best(Map<String, RoutingStrategyReport> reports) {
        return ranked(reports).stream().findFirst();
    }
}
