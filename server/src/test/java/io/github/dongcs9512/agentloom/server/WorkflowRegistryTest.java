package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.InMemoryConfigRepository;
import io.github.dongcs9512.agentloom.core.workflow.StepType;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowDefinition;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkflowRegistryTest {

    private static WorkflowDefinition flow(String id, String entryAgent) {
        return new WorkflowDefinition(id, entryAgent, List.of(
                new WorkflowStep("collect", StepType.COLLECT, List.of("incident"), 3,
                        null, null, null, null, null),
                new WorkflowStep("answer", StepType.CALL, List.of(), null,
                        null, "@routed", null, null, null)));
    }

    private static WorkflowProperties yamlWith(String id, String entryAgent) {
        WorkflowProperties.Workflow workflow = new WorkflowProperties.Workflow();
        workflow.setId(id);
        workflow.setEntryAgent(entryAgent);

        WorkflowProperties.Step collect = new WorkflowProperties.Step();
        collect.setId("collect");
        collect.setType("collect");
        collect.setSlots(List.of("incident"));

        WorkflowProperties.Step answer = new WorkflowProperties.Step();
        answer.setId("answer");
        answer.setType("call");
        answer.setAgent("@routed");

        workflow.setSteps(new ArrayList<>(List.of(collect, answer)));
        WorkflowProperties properties = new WorkflowProperties();
        properties.setItems(new ArrayList<>(List.of(workflow)));
        return properties;
    }

    @Test
    void usesDatabaseWhenPresent() {
        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        repo.saveWorkflow(flow("from-db", "intake"));
        WorkflowRegistry registry = new WorkflowRegistry(repo, yamlWith("from-yaml", "intake"), true, 0);

        WorkflowDefinition found = registry.find("intake");

        assertNotNull(found);
        assertEquals("from-db", found.id(), "数据库定义应覆盖配置文件");
    }

    @Test
    void fallsBackToConfigFile() {
        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        WorkflowRegistry registry = new WorkflowRegistry(repo, yamlWith("from-yaml", "intake"), true, 0);

        WorkflowDefinition found = registry.find("intake");

        assertNotNull(found);
        assertEquals("from-yaml", found.id());
    }

    @Test
    void loadFromDbCanBeDisabled() {
        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        repo.saveWorkflow(flow("from-db", "intake"));
        WorkflowRegistry registry = new WorkflowRegistry(repo, yamlWith("from-yaml", "intake"), false, 0);

        assertEquals("from-yaml", registry.find("intake").id(), "关掉数据库加载时只用配置文件");
    }

    @Test
    void unknownAgentReturnsNull() {
        WorkflowRegistry registry = new WorkflowRegistry(new InMemoryConfigRepository(),
                yamlWith("from-yaml", "intake"), true, 0);

        assertNull(registry.find("nobody"));
    }

    @Test
    void databaseFailureFallsBackToCachedOrConfigFile() {
        io.github.dongcs9512.agentloom.core.ConfigRepository broken =
                new io.github.dongcs9512.agentloom.core.ConfigRepository() {
                    @Override
                    public List<io.github.dongcs9512.agentloom.core.AgentConfig> loadAgents() {
                        return List.of();
                    }

                    @Override
                    public List<io.github.dongcs9512.agentloom.core.SkillConfig> loadSkills() {
                        return List.of();
                    }

                    @Override
                    public void saveAgent(io.github.dongcs9512.agentloom.core.AgentConfig config) {
                    }

                    @Override
                    public void deleteAgent(String id) {
                    }

                    @Override
                    public void saveSkill(io.github.dongcs9512.agentloom.core.SkillConfig config) {
                    }

                    @Override
                    public void deleteSkill(String name) {
                    }

                    @Override
                    public List<WorkflowDefinition> loadWorkflows() {
                        throw new IllegalStateException("数据库挂了");
                    }
                };
        WorkflowRegistry registry = new WorkflowRegistry(broken, yamlWith("from-yaml", "intake"), true, 0);

        assertEquals("from-yaml", registry.find("intake").id(), "数据库异常不应影响服务");
    }

    @Test
    void allMergesBothSources() {
        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        repo.saveWorkflow(flow("db-flow", "other-agent"));
        WorkflowRegistry registry = new WorkflowRegistry(repo, yamlWith("yaml-flow", "intake"), true, 0);

        assertEquals(2, registry.all().size());
        assertNotNull(registry.all().get("intake"));
        assertNotNull(registry.all().get("other-agent"));
    }
}
