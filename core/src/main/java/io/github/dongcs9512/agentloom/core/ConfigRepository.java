package io.github.dongcs9512.agentloom.core;

import io.github.dongcs9512.agentloom.core.workflow.WorkflowDefinition;

import java.util.List;

public interface ConfigRepository {
    List<AgentConfig> loadAgents();

    List<SkillConfig> loadSkills();

    void saveAgent(AgentConfig config);

    void deleteAgent(String id);

    void saveSkill(SkillConfig config);

    void deleteSkill(String name);

    // ---- 工作流配置（可以放数据库，也可以只放在配置文件里）----

    /** 默认不支持：只用配置文件定义工作流的场景不需要实现。 */
    default List<WorkflowDefinition> loadWorkflows() {
        return List.of();
    }

    default void saveWorkflow(WorkflowDefinition workflow) {
        throw new UnsupportedOperationException("当前配置存储不支持工作流: " + workflow.id());
    }

    default void deleteWorkflow(String id) {
    }
}
