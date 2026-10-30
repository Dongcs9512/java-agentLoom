package com.aia.server;

import com.aia.core.AgentConfig;
import com.aia.core.AgentLoop;
import com.aia.core.ConfigRepository;
import com.aia.core.DefaultToolResolver;
import com.aia.core.GuardChain;
import com.aia.core.GuardContext;
import com.aia.core.obs.MetricsRecorder;
import com.aia.core.ScenarioDispatchTool;
import com.aia.core.Skill;
import com.aia.core.SkillConfig;
import com.aia.core.SubAgentTool;
import com.aia.core.Tool;
import com.aia.core.ToolRegistry;
import com.aia.core.ToolResolver;
import com.aia.core.session.CompressedContext;
import com.aia.core.session.RecordSlotTool;
import com.aia.core.session.RouteDecision;
import com.aia.core.session.RouteResult;
import com.aia.core.session.Session;
import com.aia.core.session.SlotPromptBuilder;
import com.aia.llm.ChatMessage;
import com.aia.llm.ChatModel;
import com.aia.rag.KnowledgeSearchTool;

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

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory) {
        this(repo, tools, factory, new DefaultToolResolver(), SessionSupport.disabled());
    }

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory, ToolResolver toolResolver) {
        this(repo, tools, factory, toolResolver, SessionSupport.disabled());
    }

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory,
                        ToolResolver toolResolver, SessionSupport sessionSupport) {
        this(repo, tools, factory, toolResolver, sessionSupport, new GuardChain(List.of()),
                MetricsRecorder.noop());
    }

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory,
                        ToolResolver toolResolver, SessionSupport sessionSupport, GuardChain guardChain) {
        this(repo, tools, factory, toolResolver, sessionSupport, guardChain, MetricsRecorder.noop());
    }

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory,
                        ToolResolver toolResolver, SessionSupport sessionSupport, GuardChain guardChain,
                        MetricsRecorder metrics) {
        this.repo = repo;
        this.tools = tools;
        this.factory = factory;
        this.toolResolver = toolResolver;
        this.sessionSupport = sessionSupport == null ? SessionSupport.disabled() : sessionSupport;
        this.guardChain = guardChain == null ? new GuardChain(List.of()) : guardChain;
        this.metrics = metrics == null ? MetricsRecorder.noop() : metrics;
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
        // 并发闸门：模型调用是最贵的资源，饱和时快速失败比全部拖到超时好
        return guardChain.execute(new GuardContext(agentId),
                () -> metrics.measure("aia.agent.chat", Map.of("agent", agentId),
                        () -> doChat(agentId, sessionId, userMessage)));
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
        guardChain.execute(new GuardContext(agentId), () ->
                metrics.measure("aia.agent.chat", Map.of("agent", agentId, "stream", "true"), () -> {
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
        metrics.time("aia.stage", Math.round((System.nanoTime() - routeStart) / 1_000_000.0),
                Map.of("stage", "route"));
        metrics.count("aia.stage.decision", Map.of("decision", route.decision().name()));

        boolean slotsComplete = SlotPromptBuilder.missing(config.slots(), session).isEmpty();
        boolean cacheable = sessionSupport.hasCache() && slotsComplete && route.decision() != RouteDecision.CANNED;
        if (cacheable) {
            long cacheStart = System.nanoTime();
            Optional<String> hit = sessionSupport.cache().get(agentId, userMessage);
            metrics.time("aia.stage", Math.round((System.nanoTime() - cacheStart) / 1_000_000.0),
                    Map.of("stage", "cache"));
            metrics.count("aia.answer.cache", Map.of("result", hit.isPresent() ? "hit" : "miss"));
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
                .noneMatch(com.aia.core.session.Slot::required);
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
        metrics.count("aia.scenario.dispatch", Map.of("agent", target.get(), "source", "auto"));
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
            metrics.time("aia.stage", Math.round((System.nanoTime() - compressStart) / 1_000_000.0),
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
        List<Tool> resolved = new ArrayList<>(toolResolver.resolve(config, skills, tools));
        if (route == RouteDecision.DIRECT) {
            // 纯对话路由：拿掉知识库工具，避免模型为寒暄去检索
            resolved.removeIf(tool -> KnowledgeSearchTool.NAME.equals(tool.name()));
        }
        if (session != null && !config.slots().isEmpty()) {
            resolved.add(new RecordSlotTool(session, config.slots()));
            if (sessionSupport.hasScenarioRouter()) {
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
