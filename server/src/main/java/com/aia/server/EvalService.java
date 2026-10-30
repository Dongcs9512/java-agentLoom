package com.aia.server;

import com.aia.core.ConfigRepository;
import com.aia.core.ToolRegistry;
import com.aia.rag.KnowledgeBase;
import com.aia.rag.SearchHit;
import com.aia.rag.eval.EvalCaseResult;
import com.aia.rag.eval.EvalDataset;
import com.aia.rag.eval.EvalReport;
import com.aia.rag.eval.RagEvaluator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 跑固定评测集：先把数据集里的知识灌进知识库，再逐题检索 + 让 agent 作答，最后算命中率与关键词覆盖。
 * 评测使用无会话、无缓存的运行时，避免上一题的结果影响下一题。
 */
@Service
@ConditionalOnProperty(prefix = "aia.eval", name = "enabled", havingValue = "true")
public class EvalService {

    private final ObjectProvider<KnowledgeBase> knowledgeBase;
    private final AgentRuntime evalRuntime;
    private final int defaultTopK;

    @Value("${aia.eval.dataset-dir:evals}")
    private String datasetDir;

    public EvalService(ObjectProvider<KnowledgeBase> knowledgeBase, ConfigRepository repo,
                       ToolRegistry toolRegistry, ChatModelFactory chatModelFactory,
                       @Value("${aia.eval.top-k:5}") int defaultTopK) {
        this.knowledgeBase = knowledgeBase;
        // 评测用独立运行时：不带会话与答案缓存
        this.evalRuntime = new AgentRuntime(repo, toolRegistry, chatModelFactory);
        this.defaultTopK = defaultTopK;
    }

    public EvalDataset load(String datasetName) {
        String file = datasetName.endsWith(".json") ? datasetName : datasetName + ".json";
        return EvalDataset.load(Path.of(datasetDir, file));
    }

    public EvalReport run(String datasetName, String agentId, Integer topK, boolean ingest) {
        EvalDataset dataset = load(datasetName);
        KnowledgeBase kb = knowledgeBase.getIfAvailable();
        if (kb == null) {
            throw new IllegalStateException("未启用 RAG（aia.rag.enabled=false），无法跑检索评测");
        }
        if (ingest) {
            dataset.documents().forEach(doc -> kb.addDocument(doc.docId(), doc.text()));
        }

        int k = topK == null || topK <= 0 ? defaultTopK : topK;
        List<EvalCaseResult> results = new ArrayList<>();
        for (var evalCase : dataset.cases()) {
            List<SearchHit> hits = kb.search(evalCase.question(), k);
            String answer = agentId == null || agentId.isBlank()
                    ? null
                    : evalRuntime.chat(agentId, evalCase.question());
            results.add(RagEvaluator.score(evalCase, hits, answer));
        }
        return new EvalReport(dataset.name(), results);
    }
}
