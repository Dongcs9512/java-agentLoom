package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.AgentConfig;
import io.github.dongcs9512.agentloom.core.AgentLoop;
import io.github.dongcs9512.agentloom.core.ConfigRepository;
import io.github.dongcs9512.agentloom.core.DefaultToolResolver;
import io.github.dongcs9512.agentloom.core.GuardChain;
import io.github.dongcs9512.agentloom.core.GuardContext;
import io.github.dongcs9512.agentloom.core.obs.MetricsRecorder;
import io.github.dongcs9512.agentloom.core.ScenarioDispatchTool;
import io.github.dongcs9512.agentloom.core.Skill;
import io.github.dongcs9512.agentloom.core.SkillConfig;
import io.github.dongcs9512.agentloom.core.SubAgentTool;
import io.github.dongcs9512.agentloom.core.Tool;
import io.github.dongcs9512.agentloom.core.ToolRegistry;
import io.github.dongcs9512.agentloom.core.ToolResolver;
import io.github.dongcs9512.agentloom.core.session.CompressedContext;
import io.github.dongcs9512.agentloom.core.session.RecordSlotTool;
import io.github.dongcs9512.agentloom.core.session.RouteDecision;
import io.github.dongcs9512.agentloom.core.session.RouteResult;
import io.github.dongcs9512.agentloom.core.session.Session;
import io.github.dongcs9512.agentloom.core.session.SlotPromptBuilder;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowDefinition;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowEngine;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowHost;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowResult;
import io.github.dongcs9512.agentloom.llm.ChatMessage;
import io.github.dongcs9512.agentloom.llm.ChatModel;
import io.github.dongcs9512.agentloom.rag.KnowledgeSearchTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class AgentRuntime {

    private final ConfigRepository repo;
    private final ToolRegistry tools;
    private final ChatModelFactory factory;
    private final ToolResolver toolResolver;
    private final SessionSupport sessionSupport;
    private final GuardChain guardChain;
    private final MetricsRecorder metrics;
    private final java.util.function.Function<String, WorkflowDefinition> workflowLookup;
    private final WorkflowEngine workflowEngine = new WorkflowEngine();

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory) {
        this(repo, tools, factory, new DefaultToolResolver(), SessionSupport.disabled());
    }

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory, ToolResolver toolResolver) {
        this(repo, tools, factory, toolResolver, SessionSupport.disabled());
    }

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory,
                        ToolResolver toolResolver, SessionSupport sessionSupport) {
        this(repo, tools, factory, toolResolver, sessionSupport, new GuardChain(List.of()),
                MetricsRecorder.noop(), id -> null);
    }

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory,
                        ToolResolver toolResolver, SessionSupport sessionSupport, GuardChain guardChain) {
        this(repo, tools, factory, toolResolver, sessionSupport, guardChain, MetricsRecorder.noop(), id -> null);
    }

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory,
                        ToolResolver toolResolver, SessionSupport sessionSupport, GuardChain guardChain,
                        MetricsRecorder metrics) {
        this(repo, tools, factory, toolResolver, sessionSupport, guardChain, metrics, id -> null);
    }

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory,
                        ToolResolver toolResolver, SessionSupport sessionSupport, GuardChain guardChain,
                        MetricsRecorder metrics,
                        java.util.function.Function<String, WorkflowDefinition> workflowLookup) {
        this.repo = repo;
        this.tools = tools;
        this.factory = factory;
        this.toolResolver = toolResolver;
        this.sessionSupport = sessionSupport == null ? SessionSupport.disabled() : sessionSupport;
        this.guardChain = guardChain == null ? new GuardChain(List.of()) : guardChain;
        this.metrics = metrics == null ? MetricsRecorder.noop() : metrics;
        this.workflowLookup = workflowLookup == null ? id -> null : workflowLookup;
    }

    // ---------- 单轮问答（保持向后兼容） ----------

    public String chat(String agentId, String userMessage) {
        return chat(agentId, null, userMessage).answer();
    }

    public void streamChat(String agentId, String userMessage, Consumer<String> onDelta) {
        streamChat(agentId, null, userMessage, onDelta);
    }

    // ---------- 多轮会话 ----------

    public ChatReply chat(String agentId, String sessionId, String userMessage) {
        ChatReply waiting = recordOnlyIfHumanHandled(sessionId, userMessage);
        if (waiting != null) {
            return waiting;
        }
        WorkflowDefinition workflow = workflowLookup.apply(agentId);
        // 并发闸门：模型调用是最贵的资源，饱和时快速失败比全部拖到超时好
        return guardChain.execute(new GuardContext(agentId),
                () -> metrics.measure("agentloom.agent.chat", Map.of("agent", agentId),
                        () -> workflow == null
                                ? doChat(agentId, sessionId, userMessage)
                                : doWorkflowChat(workflow, sessionId, userMessage)));
    }

    /**
     * 会话已由人工接管时，AI 不再回复：只把用户消息记进会话，等坐席处理。
     *
     * @return 需要直接返回给调用方的结果；为 null 表示继续走 AI
     */
    private ChatReply recordOnlyIfHumanHandled(String sessionId, String userMessage) {
        if (!sessionSupport.hasSession() || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Session session = sessionSupport.store().load(sessionId).orElse(null);
        if (session == null || !session.handledByHuman()) {
            return null;
        }
        session.addMessage(ChatMessage.user(userMessage));
        sessionSupport.store().save(session);
        metrics.count("agentloom.handoff.waiting", Map.of("agent", session.agentId()));
        return ChatReply.waitingHuman(sessionId);
    }

    /**
     * 走工作流：由 {@link WorkflowEngine} 按 YAML 里定义的步骤推进，
     * 状态存在会话里，所以能跨多轮用户消息。
     */
    private ChatReply doWorkflowChat(WorkflowDefinition workflow, String sessionId, String userMessage) {
        Session session = loadOrCreateSession(sessionId, workflow.entryAgent());

        // 流程已经走完、且最后由某个专业 agent 作答：后续消息直接交给它，
        // 不要重新从"请问具体是什么事"开始问
        if ("true".equals(session.slot(WorkflowEngine.SLOT_DONE))) {
            String stickyAgent = session.slot(WorkflowEngine.SLOT_ROUTED);
            if (!stickyAgent.isBlank()) {
                String answer = runAgentTurn(loadAgent(stickyAgent), session, userMessage, false);
                metrics.count("agentloom.workflow.sticky", Map.of("agent", stickyAgent));
                if (sessionSupport.hasSession() && !"ephemeral".equals(session.id())) {
                    sessionSupport.store().save(session);
                }
                return new ChatReply(answer, sessionId, false);
            }
        }
        WorkflowHost host = new WorkflowHost() {
            @Override
            public String chat(String targetAgentId, Session targetSession, String message) {
                AgentConfig config = loadAgent(targetAgentId);
                boolean collecting = targetAgentId.equals(workflow.entryAgent());
                return runAgentTurn(config, targetSession, message, collecting);
            }

            @Override
            public String combine(String instruction) {
                // 用一个通用 agent 把多份并行结果合成一段话（不收集新信息）
                return runAgentTurn(loadAgent(workflow.entryAgent()), session, instruction, false);
            }

            @Override
            public java.util.Optional<String> route(Session targetSession) {
                java.util.Optional<String> routed = sessionSupport.hasScenarioRouter()
                        ? sessionSupport.scenarioRouter().route("", targetSession)
                        : java.util.Optional.empty();
                routed.ifPresent(agent -> metrics.count("agentloom.scenario.dispatch",
                        Map.of("agent", agent, "source", "workflow")));
                return routed;
            }
        };

        WorkflowResult result = workflowEngine.step(workflow, session, userMessage, host);
        metrics.count("agentloom.workflow.step", Map.of("workflow", workflow.id(),
                "finished", String.valueOf(result.finished())));
        if (sessionSupport.hasSession() && !"ephemeral".equals(session.id())) {
            sessionSupport.store().save(session);
        }
        return new ChatReply(result.answer(), sessionId, false);
    }

    /** 在工作流里跑一轮指定 agent，并把消息记进会话。 */
    private String runAgentTurn(AgentConfig config, Session session, String userMessage, boolean includeSlotPrompt) {
        List<Skill> skills = resolveSkills(config.skillNames());
        AgentLoop loop = buildLoop(config, session, RouteDecision.RETRIEVE, factory.create(config.model()),
                skills, false);
        List<ChatMessage> conversation = buildConversation(config, session, loop, userMessage, includeSlotPrompt);
        String answer = loop.run(conversation);
        session.addMessage(ChatMessage.user(userMessage));
        session.addMessage(ChatMessage.assistant(answer));
        return answer;
    }

    private ChatReply doChat(String agentId, String sessionId, String userMessage) {
        PreparedTurn turn = prepare(agentId, sessionId, userMessage);
        String answer;
        if (turn.route().decision() == RouteDecision.CANNED) {
            answer = turn.route().reply();
        } else if (turn.cachedAnswer() != null) {
            answer = turn.cachedAnswer();
        } else {
            answer = turn.loop().run(turn.conversation());
        }
        persist(turn, userMessage, answer);
        return new ChatReply(answer, sessionId, turn.cachedAnswer() != null);
    }

    public void streamChat(String agentId, String sessionId, String userMessage, Consumer<String> onDelta) {
        if (recordOnlyIfHumanHandled(sessionId, userMessage) != null) {
            return;
        }
        WorkflowDefinition workflow = workflowLookup.apply(agentId);
        if (workflow != null) {
            // 工作流涉及多步编排，v1 整段推送；逐字流式只在单 agent 路径支持
            onDelta.accept(chat(agentId, sessionId, userMessage).answer());
            return;
        }
        guardChain.execute(new GuardContext(agentId), () ->
                metrics.measure("agentloom.agent.chat", Map.of("agent", agentId, "stream", "true"), () -> {
                    doStreamChat(agentId, sessionId, userMessage, onDelta);
                    return null;
                }));
    }

    private void doStreamChat(String agentId, String sessionId, String userMessage, Consumer<String> onDelta) {
        PreparedTurn turn = prepare(agentId, sessionId, userMessage);
        String answer;
        if (turn.route().decision() == RouteDecision.CANNED) {
            answer = turn.route().reply();
            onDelta.accept(answer);
        } else if (turn.cachedAnswer() != null) {
            answer = turn.cachedAnswer();
            // 缓存命中时整段推送，客户端体验与流式一致
            onDelta.accept(answer);
        } else {
            StringBuilder sb = new StringBuilder();
            turn.loop().stream(turn.conversation(), chunk -> {
                sb.append(chunk);
                onDelta.accept(chunk);
            });
            answer = sb.toString();
        }
        persist(turn, userMessage, answer);
    }

    // ---------- 编排 ----------

    /**
     * 把另一个 agent 包装成工具，注册进 {@link ToolRegistry} 后即可被子 agent 委派调用。
     */
    public SubAgentTool subAgentTool(String agentId, String description) {
        return SubAgentTool.of(agentId, description, () -> buildLoop(loadAgent(agentId), null, RouteDecision.RETRIEVE));
    }

    // ---------- 配置管理 ----------

    public List<AgentConfig> listAgents() {
        return repo.loadAgents();
    }

    public List<SkillConfig> listSkills() {
        return repo.loadSkills();
    }

    public void saveAgent(AgentConfig config) {
        repo.saveAgent(config);
    }

    public void deleteAgent(String id) {
        repo.deleteAgent(id);
    }

    public void saveSkill(SkillConfig config) {
        repo.saveSkill(config);
    }

    public void deleteSkill(String name) {
        repo.deleteSkill(name);
    }

    // ---------- 内部实现 ----------

    private record PreparedTurn(
            AgentConfig config,
            Session session,
            RouteResult route,
            String cachedAnswer,
            AgentLoop loop,
            List<ChatMessage> conversation,
            boolean cacheable) {
    }

    private PreparedTurn prepare(String agentId, String sessionId, String userMessage) {
        AgentConfig config = loadAgent(agentId);
        Session session = loadOrCreateSession(sessionId, agentId);

        long routeStart = System.nanoTime();
        RouteResult route = sessionSupport.hasRouter()
                ? sessionSupport.router().route(userMessage, session)
                : RouteResult.retrieve();
        metrics.time("agentloom.stage", Math.round((System.nanoTime() - routeStart) / 1_000_000.0),
                Map.of("stage", "route"));
        metrics.count("agentloom.stage.decision", Map.of("decision", route.decision().name()));

        boolean slotsComplete = SlotPromptBuilder.missing(config.slots(), session).isEmpty();
        boolean cacheable = sessionSupport.hasCache() && slotsComplete && route.decision() != RouteDecision.CANNED;
        if (cacheable) {
            long cacheStart = System.nanoTime();
            Optional<String> hit = sessionSupport.cache().get(agentId, userMessage);
            metrics.time("agentloom.stage", Math.round((System.nanoTime() - cacheStart) / 1_000_000.0),
                    Map.of("stage", "cache"));
            metrics.count("agentloom.answer.cache", Map.of("result", hit.isPresent() ? "hit" : "miss"));
            if (hit.isPresent()) {
                return new PreparedTurn(config, session, route, hit.get(), null, List.of(), true);
            }
        }

        // 必填信息已齐且还没分派过 → 确定性交给专业 agent，不依赖模型主动调用工具
        AgentLoop loop = tryScenarioDispatch(config, session, userMessage);
        boolean includeSlotPrompt = loop == null;
        if (loop == null) {
            List<Skill> skills = resolveSkills(config.skillNames());
            loop = buildLoop(config, session, route.decision(), factory.create(config.model()), skills);
        }
        List<ChatMessage> conversation = buildConversation(config, session, loop, userMessage, includeSlotPrompt);
        return new PreparedTurn(config, session, route, null, loop, conversation, cacheable);
    }

    /**
     * @return 分派成功时返回专业 agent 的循环；否则返回 null，由调用方走常规流程
     */
    private AgentLoop tryScenarioDispatch(AgentConfig config, Session session, String userMessage) {
        if (!sessionSupport.hasScenarioRouter() || config.slots().isEmpty()) {
            return null;
        }
        if (!session.slot(ScenarioDispatchTool.SCENARIO_SLOT).isBlank()) {
            return null; // 本次会话已经分派过
        }
        boolean requiredAllFilled = SlotPromptBuilder.missing(config.slots(), session).stream()
                .noneMatch(io.github.dongcs9512.agentloom.core.session.Slot::required);
        if (!requiredAllFilled) {
            return null;
        }
        Optional<String> target = sessionSupport.scenarioRouter().route(userMessage, session);
        if (target.isEmpty()) {
            return null;
        }
        AgentLoop specialist = buildSpecialistLoop(target.get());
        if (specialist == null) {
            return null;
        }
        session.putSlot(ScenarioDispatchTool.SCENARIO_SLOT, target.get());
        metrics.count("agentloom.scenario.dispatch", Map.of("agent", target.get(), "source", "auto"));
        return specialist;
    }

    private Session loadOrCreateSession(String sessionId, String agentId) {
        if (!sessionSupport.hasSession() || sessionId == null || sessionId.isBlank()) {
            return new Session(sessionId == null ? "ephemeral" : sessionId, agentId);
        }
        return sessionSupport.store().load(sessionId)
                .orElseGet(() -> new Session(sessionId, agentId));
    }

    private void persist(PreparedTurn turn, String userMessage, String answer) {
        Session session = turn.session();
        session.addMessage(ChatMessage.user(userMessage));
        session.addMessage(ChatMessage.assistant(answer));

        if (turn.cacheable() && turn.cachedAnswer() == null && answer != null && !answer.isBlank()) {
            sessionSupport.cache().put(turn.config().id(), userMessage, answer);
        }
        if (sessionSupport.hasSession() && !"ephemeral".equals(session.id())) {
            sessionSupport.store().save(session);
        }
    }

    private List<ChatMessage> buildConversation(AgentConfig config, Session session, AgentLoop loop,
                                                String userMessage, boolean includeSlotPrompt) {
        String summary = session.summary();
        if (sessionSupport.hasCompressor() && !session.messages().isEmpty()) {
            long compressStart = System.nanoTime();
            CompressedContext compressed = sessionSupport.compressor().compress(summary, session.messages());
            metrics.time("agentloom.stage", Math.round((System.nanoTime() - compressStart) / 1_000_000.0),
                    Map.of("stage", "compress"));
            if (compressed.messages().size() != session.messages().size()
                    || !Objects.equals(compressed.summary(), summary)) {
                session.replaceHistory(compressed.summary(), compressed.messages());
            }
            summary = compressed.summary();
        }

        StringBuilder system = new StringBuilder(loop.effectiveSystemPrompt());
        if (includeSlotPrompt && !config.slots().isEmpty()) {
            system.append(SlotPromptBuilder.build(config.slots(), session));
        }
        if (!includeSlotPrompt) {
            // 分派给专业 agent 时，把接待阶段收集到的案情一并带上
            StringBuilder caseInfo = new StringBuilder();
            session.slots().forEach((name, value) -> {
                if (!name.startsWith("__")) {
                    caseInfo.append("- ").append(name).append("：").append(value).append('\n');
                }
            });
            if (!caseInfo.isEmpty()) {
                system.append("\n\n【接待阶段收集的案情】\n").append(caseInfo);
            }
        }
        if (summary != null && !summary.isBlank()) {
            system.append("\n\n【此前对话摘要】\n").append(summary);
        }

        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(ChatMessage.system(system.toString()));
        conversation.addAll(session.messages());
        conversation.add(ChatMessage.user(userMessage));
        return conversation;
    }

    private AgentConfig loadAgent(String agentId) {
        return repo.loadAgents().stream()
                .filter(a -> a.id().equals(agentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("agent 不存在: " + agentId));
    }

    private AgentLoop buildLoop(AgentConfig config, Session session, RouteDecision route) {
        List<Skill> skills = resolveSkills(config.skillNames());
        return buildLoop(config, session, route, factory.create(config.model()), skills);
    }

    private AgentLoop buildLoop(AgentConfig config, Session session, RouteDecision route, ChatModel model,
                                List<Skill> skills) {
        return buildLoop(config, session, route, model, skills, true);
    }

    private AgentLoop buildLoop(AgentConfig config, Session session, RouteDecision route, ChatModel model,
                                List<Skill> skills, boolean includeDispatchTool) {
        List<Tool> resolved = new ArrayList<>(toolResolver.resolve(config, skills, tools));
        if (route == RouteDecision.DIRECT) {
            // 纯对话路由：拿掉知识库工具，避免模型为寒暄去检索
            resolved.removeIf(tool -> KnowledgeSearchTool.NAME.equals(tool.name()));
        }
        if (session != null && !config.slots().isEmpty()) {
            resolved.add(new RecordSlotTool(session, config.slots()));
            if (includeDispatchTool && sessionSupport.hasScenarioRouter()) {
                // 接待类 agent 收集完信息后，由路由把案件分给对应的专业 agent
                resolved.add(new ScenarioDispatchTool(sessionSupport.scenarioRouter(), session,
                        this::buildSpecialistLoop, metrics));
            }
        }
        return new AgentLoop(model, resolved, skills, config.systemPrompt(), metrics, config.model());
    }

    private AgentLoop buildSpecialistLoop(String agentId) {
        Optional<AgentConfig> specialist = repo.loadAgents().stream()
                .filter(a -> a.id().equals(agentId))
                .findFirst();
        if (specialist.isEmpty()) {
            return null;
        }
        return buildLoop(specialist.get(), null, RouteDecision.RETRIEVE);
    }

    private List<Skill> resolveSkills(List<String> skillNames) {
        return repo.loadSkills().stream()
                .filter(s -> skillNames != null && skillNames.contains(s.name()))
                .map(this::toSkill)
                .toList();
    }

    private Skill toSkill(SkillConfig config) {
        List<Tool> resolved = config.toolNames().stream()
                .map(tools::get)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        return new Skill() {
            @Override
            public String name() {
                return config.name();
            }

            @Override
            public String description() {
                return config.description();
            }

            @Override
            public String instructions() {
                return config.instructions();
            }

            @Override
            public List<Tool> tools() {
                return resolved;
            }

            @Override
            public Map<String, Object> resources() {
                return Map.of();
            }
        };
    }
}
