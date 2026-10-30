package com.aia.server;

import com.aia.rag.eval.EvalReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/eval")
@ConditionalOnProperty(prefix = "aia.eval", name = "enabled", havingValue = "true")
public class EvalController {

    public record RunRequest(String dataset, String agentId, Integer topK, Boolean ingest) {
    }

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody RunRequest request) {
        String dataset = request.dataset() == null || request.dataset().isBlank() ? "labor-law" : request.dataset();
        boolean ingest = request.ingest() == null || request.ingest();
        EvalReport report = evalService.run(dataset, request.agentId(), request.topK(), ingest);
        return Map.of("report", report, "summary", report.summary(0.6));
    }

    @PostMapping("/run/{dataset}")
    public Map<String, Object> runByName(@PathVariable String dataset) {
        EvalReport report = evalService.run(dataset, null, null, false);
        return Map.of("report", report, "summary", report.summary(0.6));
    }
}
