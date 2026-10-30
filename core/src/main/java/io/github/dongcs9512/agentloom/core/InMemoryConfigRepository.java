package io.github.dongcs9512.agentloom.core;

import io.github.dongcs9512.agentloom.core.workflow.WorkflowDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryConfigRepository implements ConfigRepository {

    private final Map<String, AgentConfig> agents = new LinkedHashMap<>();
    private final Map<String, SkillConfig> skills = new LinkedHashMap<>();
    private final Map<String, WorkflowDefinition> workflows = new LinkedHashMap<>();

    @Override
    public List<AgentConfig> loadAgents() {
        return new ArrayList<>(agents.values());
    }

    @Override
    public List<SkillConfig> loadSkills() {
        return new ArrayList<>(skills.values());
    }

    @Override
    public void saveAgent(AgentConfig config) {
        if (config.id() == null || config.id().isBlank()) {
            throw new IllegalArgumentException("agent id 不能为空");
        }
        agents.put(config.id(), config);
    }

    @Override
    public void deleteAgent(String id) {
        agents.remove(id);
    }

    @Override
    public void saveSkill(SkillConfig config) {
        if (config.name() == null || config.name().isBlank()) {
            throw new IllegalArgumentException("skill name 不能为空");
        }
        skills.put(config.name(), config);
    }

    @Override
    public void deleteSkill(String name) {
        skills.remove(name);
    }

    @Override
    public List<WorkflowDefinition> loadWorkflows() {
        return new ArrayList<>(workflows.values());
    }

    @Override
    public void saveWorkflow(WorkflowDefinition workflow) {
        if (workflow.id() == null || workflow.id().isBlank()) {
            throw new IllegalArgumentException("工作流 id 不能为空");
        }
        workflows.put(workflow.id(), workflow);
    }

    @Override
    public void deleteWorkflow(String id) {
        workflows.remove(id);
    }
}
