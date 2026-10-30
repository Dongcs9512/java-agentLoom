package io.github.dongcs9512.agentloom.config;

import io.github.dongcs9512.agentloom.core.workflow.StepType;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowDefinition;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowStep;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcWorkflowRepositoryTest {

    private JdbcConfigRepository repo;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:wfdb-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        repo = new JdbcConfigRepository(ds);
    }

    private WorkflowDefinition flow(String id) {
        return new WorkflowDefinition(id, "intake", List.of(
                new WorkflowStep("collect", StepType.COLLECT, List.of("incident", "when"), 3,
                        null, null, null, null, null),
                new WorkflowStep("dispatch", StepType.DISPATCH, List.of(), null,
                        "cascade", null, null, null, null),
                new WorkflowStep("answer", StepType.CALL, List.of(), null,
                        null, "@routed", null, null, null)));
    }

    @Test
    void roundTripsWorkflow() {
        repo.saveWorkflow(flow("w1"));

        List<WorkflowDefinition> loaded = repo.loadWorkflows();

        assertEquals(1, loaded.size());
        WorkflowDefinition definition = loaded.get(0);
        assertEquals("w1", definition.id());
        assertEquals("intake", definition.entryAgent());
        assertEquals(3, definition.steps().size());
        assertEquals(StepType.COLLECT, definition.steps().get(0).type());
        assertEquals(List.of("incident", "when"), definition.steps().get(0).slots());
        assertEquals(3, definition.steps().get(0).maxTurnsOrDefault());
        assertTrue(definition.steps().get(2).usesRoutedAgent());
    }

    @Test
    void savingTwiceReplaces() {
        repo.saveWorkflow(flow("w1"));
        repo.saveWorkflow(flow("w1"));

        assertEquals(1, repo.loadWorkflows().size());
    }

    @Test
    void deleteRemoves() {
        repo.saveWorkflow(flow("w1"));

        repo.deleteWorkflow("w1");

        assertTrue(repo.loadWorkflows().isEmpty());
    }

    @Test
    void invalidWorkflowIsRejectedBeforeWrite() {
        WorkflowDefinition broken = new WorkflowDefinition("bad", "intake", List.of(
                new WorkflowStep("check", StepType.CONDITION, List.of(), null, null, null,
                        "amount > 100", "", "")));

        assertThrows(IllegalArgumentException.class, () -> repo.saveWorkflow(broken));
        assertTrue(repo.loadWorkflows().isEmpty(), "校验失败不应写进库");
    }

    @Test
    void otherConfigStillWorksWithWorkflowTable() {
        repo.saveAgent(new io.github.dongcs9512.agentloom.core.AgentConfig(
                "a1", "p", "m", List.of(), List.of(), null, null));

        assertEquals(1, repo.loadAgents().size());
        assertTrue(repo.loadWorkflows().isEmpty());
    }
}
