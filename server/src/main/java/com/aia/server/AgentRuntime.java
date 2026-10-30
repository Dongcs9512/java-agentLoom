package com.aia.server;

import com.aia.core.AgentConfig;
import com.aia.core.AgentLoop;
import com.aia.core.ConfigRepository;
import com.aia.core.Skill;
import com.aia.core.SkillConfig;
import com.aia.core.Tool;
import com.aia.core.ToolRegistry;
import com.aia.llm.ChatModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AgentRuntime {

    private final ConfigRepository repo;
    private final ToolRegistry tools;
    private final ChatModelFactory factory;

    public AgentRuntime(ConfigRepository repo, ToolRegistry tools, ChatModelFactory factory) {
        this.repo = repo;
        this.tools = tools;
        this.factory = factory;
    }

    public String chat(String agentId, String userMessage) {
        return buildLoop(agentId).run(userMessage);
    }

    public void streamChat(String agentId, String userMessage, java.util.function.Consumer<String> onDelta) {
        buildLoop(agentId).stream(userMessage, onDelta);
    }

    private AgentLoop buildLoop(String agentId) {
        AgentConfig config = repo.loadAgents().stream()
                .filter(a -> a.id().equals(agentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("agent 不存在: " + agentId));
        ChatModel model = factory.create(config.model());
        List<Skill> skills = resolveSkills(config.skillNames());
        return new AgentLoop(model, tools, skills, config.systemPrompt());
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
}
