package com.aia.server;

import com.aia.rag.eval.DialogueReport;
import com.aia.rag.eval.EvalGate;
import com.aia.rag.eval.EvalReport;
import com.aia.rag.eval.EvalThresholds;
import com.aia.rag.eval.GateResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/eval")
@ConditionalOnProperty(prefix = "aia.eval", name = "enabled", havingValue = "true")
public class EvalController {

    public record RunRequest(String dataset, String agentId, Integer topK, Boolean ingest) {
    }

    private final EvalService evalService;

    @Value("${aia.eval.thresholds.retrieval-hit-rate:0.95}")
    private double retrievalHitRateThreshold;

    @Value("${aia.eval.thresholds.keyword-coverage:0.85}")
    private double keywordCoverageThreshold;

    @Value("${aia.eval.thresholds.pass-rate:0.90}")
    private double passRateThreshold;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
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
