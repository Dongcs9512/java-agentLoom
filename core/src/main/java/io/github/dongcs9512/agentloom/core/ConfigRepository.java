package io.github.dongcs9512.agentloom.core;

import java.util.List;

public interface ConfigRepository {
    List<AgentConfig> loadAgents();

    List<SkillConfig> loadSkills();

    void saveAgent(AgentConfig config);

    void deleteAgent(String id);

    void saveSkill(SkillConfig config);

    void deleteSkill(String name);
}
