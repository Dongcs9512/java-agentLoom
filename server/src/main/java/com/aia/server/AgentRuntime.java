package com.aia.server;

import com.aia.core.AgentConfig;
import com.aia.core.AgentLoop;
import com.aia.core.ConfigRepository;
import com.aia.core.DefaultToolResolver;
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

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory) {
        this(repo, tools, factory, new DefaultToolResolver(), SessionSupport.disabled());
    }

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory, ToolResolver toolResolver) {
        this(repo, tools, factory, toolResolver, SessionSupport.disabled());
    }

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory,
                        ToolResolver toolResolver, SessionSupport sessionSupport) {
        this.repo = repo;
        this.tools = tools;
        this.factory = factory;
        this.toolResolver = toolResolver;
        this.sessionSupport = sessionSupport == null ? SessionSupport.disabled() : sessionSupport;
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

        RouteResult route = sessionSupport.hasRouter()
                ? sessionSupport.router().route(userMessage, session)
                : RouteResult.retrieve();

        boolean slotsComplete = SlotPromptBuilder.missing(config.slots(), session).isEmpty();
        boolean cacheable = sessionSupport.hasCache() && slotsComplete && route.decision() != RouteDecision.CANNED;
        if (cacheable) {
            Optional<String> hit = sessionSupport.cache().get(agentId, userMessage);
            if (hit.isPresent()) {
                return new PreparedTurn(config, session, route, hit.get(), null, List.of(), true);
            }
        }

        List<Skill> skills = resolveSkills(config.skillNames());
        AgentLoop loop = buildLoop(config, session, route.decision(), factory.create(config.model()), skills);
        List<ChatMessage> conversation = buildConversation(config, session, loop, userMessage);
        return new PreparedTurn(config, session, route, null, loop, conversation, cacheable);
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
                                                String userMessage) {
        String summary = session.summary();
        if (sessionSupport.hasCompressor() && !session.messages().isEmpty()) {
            CompressedContext compressed = sessionSupport.compressor().compress(summary, session.messages());
            if (compressed.messages().size() != session.messages().size()
                    || !Objects.equals(compressed.summary(), summary)) {
                session.replaceHistory(compressed.summary(), compressed.messages());
            }
            summary = compressed.summary();
        }

        StringBuilder system = new StringBuilder(loop.effectiveSystemPrompt());
        if (!config.slots().isEmpty()) {
            system.append(SlotPromptBuilder.build(config.slots(), session));
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
        }
        return new AgentLoop(model, resolved, skills, config.systemPrompt());
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
