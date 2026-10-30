package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.ConfigRepository;
import io.github.dongcs9512.agentloom.core.DefaultToolResolver;
import io.github.dongcs9512.agentloom.core.ToolRegistry;
import io.github.dongcs9512.agentloom.core.session.InMemorySessionStore;
import io.github.dongcs9512.agentloom.core.session.RuleBasedRetrievalRouter;
import io.github.dongcs9512.agentloom.core.session.Session;
import io.github.dongcs9512.agentloom.rag.KnowledgeBase;
import io.github.dongcs9512.agentloom.rag.SearchHit;
import io.github.dongcs9512.agentloom.rag.eval.DialogueCase;
import io.github.dongcs9512.agentloom.rag.eval.DialogueEvaluator;
import io.github.dongcs9512.agentloom.rag.eval.DialogueReport;
import io.github.dongcs9512.agentloom.rag.eval.DialogueResult;
import io.github.dongcs9512.agentloom.rag.eval.EvalCaseResult;
import io.github.dongcs9512.agentloom.rag.eval.EvalDataset;
import io.github.dongcs9512.agentloom.rag.eval.EvalReport;
import io.github.dongcs9512.agentloom.rag.eval.RagEvaluator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 跑固定评测集。
 *
 * <p>单轮评测用无会话、无缓存的运行时；多轮评测用独立的内存会话，每组对话互不影响。
 */
@Service
@ConditionalOnProperty(prefix = "agentloom.eval", name = "enabled", havingValue = "true")
public class EvalService {

    private static final double KEYWORD_THRESHOLD = 0.6;

    private final ObjectProvider<KnowledgeBase> knowledgeBase;
    private final ConfigRepository repo;
    private final ToolRegistry toolRegistry;
    private final ChatModelFactory chatModelFactory;
    private final ScenarioSettings scenarioSettings;
    private final ScenarioRouterFactory scenarioRouterFactory;
    private final AgentRuntime evalRuntime;
    private final int defaultTopK;

    @Value("${agentloom.eval.dataset-dir:evals}")
    private String datasetDir;

    public EvalService(ObjectProvider<KnowledgeBase> knowledgeBase, ConfigRepository repo,
                       ToolRegistry toolRegistry, ChatModelFactory chatModelFactory,
                       ScenarioSettings scenarioSettings, ScenarioRouterFactory scenarioRouterFactory,
                       @Value("${agentloom.eval.top-k:5}") int defaultTopK) {
        this.knowledgeBase = knowledgeBase;
        this.repo = repo;
        this.toolRegistry = toolRegistry;
        this.chatModelFactory = chatModelFactory;
        this.scenarioSettings = scenarioSettings;
        this.scenarioRouterFactory = scenarioRouterFactory;
        // 单轮评测用独立运行时：不带会话与答案缓存
        this.evalRuntime = new AgentRuntime(repo, toolRegistry, chatModelFactory);
        this.defaultTopK = defaultTopK;
    }

    public EvalDataset load(String datasetName) {
        String file = datasetName.endsWith(".json") ? datasetName : datasetName + ".json";
        return EvalDataset.load(Path.of(datasetDir, file));
    }

    public EvalReport run(String datasetName, String agentId, Integer topK, boolean ingest) {
        EvalDataset dataset = prepare(datasetName, ingest);
        KnowledgeBase kb = requireKnowledgeBase();

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

    public DialogueReport runDialogues(String datasetName, boolean ingest) {
        EvalDataset dataset = prepare(datasetName, ingest);

        List<DialogueResult> results = new ArrayList<>();
        for (DialogueCase dialogue : dataset.dialogues()) {
            results.add(runOneDialogue(dialogue));
        }
        return new DialogueReport(dataset.name(), results);
    }

    private DialogueResult runOneDialogue(DialogueCase dialogue) {
        InMemorySessionStore store = new InMemorySessionStore();
        AgentRuntime runtime = new AgentRuntime(repo, toolRegistry, chatModelFactory, new DefaultToolResolver(),
                new SessionSupport(store, null,
                        new RuleBasedRetrievalRouter("您好，请问有什么可以帮您？", List.of()), null,
                        scenarioSettings.enabled() ? scenarioRouterFactory.create() : null));
        String sessionId = "eval-" + dialogue.id();

        List<DialogueResult.DialogueTurnResult> turns = new ArrayList<>();
        StringBuilder transcript = new StringBuilder();
        for (DialogueCase.Turn turn : dialogue.turns()) {
            String answer = runtime.chat(dialogue.agentId(), sessionId, turn.user()).answer();
            turns.add(DialogueEvaluator.scoreTurn(turn.user(), answer, turn.expectKeywords()));
            transcript.append(answer == null ? "" : answer).append('\n');
        }

        Session session = store.load(sessionId).orElse(new Session(sessionId, dialogue.agentId()));
        List<String> filled = dialogue.requiredSlots().stream()
                .filter(slot -> !session.slot(slot).isBlank())
                .toList();
        String routed = session.slot(io.github.dongcs9512.agentloom.core.ScenarioDispatchTool.SCENARIO_SLOT);
        // 覆盖率按整段对话算：分析往往在中间某轮就给出了，最后一轮可能只是追问
        double answerCoverage = DialogueEvaluator
                .scoreTurn("", transcript.toString(), dialogue.finalExpectedKeywords()).keywordCoverage();
        return DialogueResult.of(dialogue, turns, dialogue.requiredSlots(), filled,
                answerCoverage, KEYWORD_THRESHOLD, routed.isBlank() ? null : routed);
    }

    private EvalDataset prepare(String datasetName, boolean ingest) {
        EvalDataset dataset = load(datasetName);
        if (ingest) {
            KnowledgeBase kb = requireKnowledgeBase();
            dataset.documents().forEach(doc -> kb.addDocument(doc.docId(), doc.text()));
        }
        return dataset;
    }

    private KnowledgeBase requireKnowledgeBase() {
        KnowledgeBase kb = knowledgeBase.getIfAvailable();
        if (kb == null) {
            throw new IllegalStateException("未启用 RAG（aia.rag.enabled=false），无法跑知识库评测");
        }
        return kb;
    }
}
