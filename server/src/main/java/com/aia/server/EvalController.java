package com.aia.server;

import com.aia.rag.eval.DialogueReport;
import com.aia.rag.eval.EvalGate;
import com.aia.rag.eval.EvalReport;
import com.aia.rag.eval.EvalThresholds;
import com.aia.rag.eval.GateResult;
import com.aia.rag.eval.RoutingStrategyReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/eval")
@ConditionalOnProperty(prefix = "aia.eval", name = "enabled", havingValue = "true")
public class EvalController {

    public record RunRequest(String dataset, String agentId, Integer topK, Boolean ingest, String llmModel) {
    }

    public record CacheRunRequest(String dataset, Integer requests, Integer hotPercent, Integer seed) {
    }

    private final EvalService evalService;
    private final RoutingBenchmarkService routingBenchmarkService;
    private final CacheBenchmarkService cacheBenchmarkService;

    @Value("${aia.eval.thresholds.retrieval-hit-rate:0.95}")
    private double retrievalHitRateThreshold;

    @Value("${aia.eval.thresholds.keyword-coverage:0.85}")
    private double keywordCoverageThreshold;

    @Value("${aia.eval.thresholds.pass-rate:0.90}")
    private double passRateThreshold;

    public EvalController(EvalService evalService, RoutingBenchmarkService routingBenchmarkService,
                          CacheBenchmarkService cacheBenchmarkService) {
        this.evalService = evalService;
        this.routingBenchmarkService = routingBenchmarkService;
        this.cacheBenchmarkService = cacheBenchmarkService;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody RunRequest request) {
        String dataset = datasetOf(request);
        EvalReport report = evalService.run(dataset, request.agentId(), request.topK(), ingestOf(request));
        GateResult gate = EvalGate.check(report, thresholds());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("report", report);
        body.put("summary", report.summary(0.6));
        body.put("retrievalHitRate", report.retrievalHitRate());
        body.put("averageKeywordCoverage", report.averageKeywordCoverage());
        body.put("passRate", report.passRate(0.6));
        body.put("gate", gate);
        return body;
    }

    @PostMapping("/dialogue")
    public Map<String, Object> runDialogue(@RequestBody RunRequest request) {
        DialogueReport report = evalService.runDialogues(datasetOf(request), ingestOf(request));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("report", report);
        body.put("summary", report.summary());
        body.put("passRate", report.passRate());
        body.put("averageSlotCompletion", report.averageSlotCompletion());
        return body;
    }

    /**
     * 场景路由横向对比：关键词 / 向量语义 / LLM 分类 / 级联，比准确率与延迟。
     */
    @PostMapping("/routing")
    public Map<String, Object> runRouting(@RequestBody(required = false) RunRequest request) {
        String dataset = request == null || request.dataset() == null || request.dataset().isBlank()
                ? "routing" : request.dataset();
        String llmModel = request == null || request.llmModel() == null || request.llmModel().isBlank()
                ? "deepseek-chat" : request.llmModel();

        Map<String, RoutingStrategyReport> reports = routingBenchmarkService.run(dataset, llmModel);
        List<RoutingStrategyReport> ranking = RoutingBenchmarkService.ranked(reports);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ranking", ranking.stream()
                .map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("strategy", r.strategy());
                    item.put("accuracy", r.accuracy());
                    item.put("avgLatencyMs", r.avgLatencyMs());
                    item.put("p95LatencyMs", r.p95LatencyMs());
                    item.put("mistakes", r.mistakes());
                    return item;
                })
                .toList());
        body.put("summary", ranking.stream().map(RoutingStrategyReport::summary).toList());
        body.put("recommended", ranking.isEmpty() ? null : ranking.get(0).strategy());
        return body;
    }

    /**
     * 答案缓存收益评估：热门问题占比可调，对比精确缓存与多阈值的语义缓存。
     */
    @PostMapping("/cache")
    public Map<String, Object> runCache(@RequestBody(required = false) CacheRunRequest request) {
        String dataset = request == null || request.dataset() == null || request.dataset().isBlank()
                ? "cache-traffic" : request.dataset();
        return cacheBenchmarkService.run(dataset,
                request == null ? null : request.requests(),
                request == null ? null : request.hotPercent(),
                request == null ? null : request.seed());
    }

    private EvalThresholds thresholds() {
        return new EvalThresholds(retrievalHitRateThreshold, keywordCoverageThreshold, passRateThreshold);
    }

    private static String datasetOf(RunRequest request) {
        return request.dataset() == null || request.dataset().isBlank() ? "labor-law" : request.dataset();
    }

    private static boolean ingestOf(RunRequest request) {
        return request.ingest() == null || request.ingest();
    }
}
