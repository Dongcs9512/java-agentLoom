package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.workflow.StepType;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowPropertiesTest {

    private static WorkflowProperties.Step step(String id, String type) {
        WorkflowProperties.Step step = new WorkflowProperties.Step();
        step.setId(id);
        step.setType(type);
        return step;
    }

    private static WorkflowProperties.Workflow workflow(String id, String entryAgent,
                                                        WorkflowProperties.Step... steps) {
        WorkflowProperties.Workflow workflow = new WorkflowProperties.Workflow();
        workflow.setId(id);
        workflow.setEntryAgent(entryAgent);
        workflow.setSteps(new ArrayList<>(List.of(steps)));
        return workflow;
    }

    private static WorkflowProperties with(WorkflowProperties.Workflow... workflows) {
        WorkflowProperties properties = new WorkflowProperties();
        properties.setItems(new ArrayList<>(List.of(workflows)));
        return properties;
    }

    @Test
    void convertsValidWorkflow() {
        WorkflowProperties.Step collect = step("collect", "collect");
        collect.setSlots(List.of("incident"));
        collect.setMaxTurns(3);
        WorkflowProperties.Step call = step("answer", "call");
        call.setAgent("@routed");

        Map<String, WorkflowDefinition> definitions =
                with(workflow("flow", "intake", collect, call)).toDefinitions();

        WorkflowDefinition definition = definitions.get("intake");
        assertEquals("flow", definition.id());
        assertEquals(2, definition.steps().size());
        assertEquals(StepType.COLLECT, definition.steps().get(0).type());
        assertEquals(3, definition.steps().get(0).maxTurnsOrDefault());
        assertTrue(definition.steps().get(1).usesRoutedAgent());
    }

    @Test
    void typeIsCaseInsensitive() {
        WorkflowProperties.Step call = step("answer", "CALL");
        call.setAgent("@routed");

        assertEquals(StepType.CALL, with(workflow("f", "intake", call)).toDefinitions()
                .get("intake").steps().get(0).type());
    }

    @Test
    void unknownTypeIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> with(workflow("f", "intake", step("s", "loop"))).toDefinitions());
        assertTrue(ex.getMessage().contains("loop"), ex.getMessage());
    }

    @Test
    void missingEntryAgentIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> with(workflow("f", null, step("s", "collect"))).toDefinitions());
    }

    @Test
    void invalidConditionIsCaughtAtLoadTime() {
        WorkflowProperties.Step condition = step("check", "condition");
        condition.setWhen("amount > 100");     // 少了 slots. 前缀
        condition.setThen("");
        condition.setOtherwise("");

        assertThrows(IllegalArgumentException.class,
                () -> with(workflow("f", "intake", condition)).toDefinitions());
    }

    @Test
    void paramsAreCarriedIntoDefinition() {
        WorkflowProperties.Step condition = step("check", "condition");
        condition.setWhen("slots.amount > params.handoff-threshold");
        condition.setThen("");
        condition.setOtherwise("");

        WorkflowProperties.Workflow workflow = workflow("f", "intake", condition);
        workflow.setParams(Map.of("handoff-threshold", "10000"));

        WorkflowDefinition definition = with(workflow).toDefinitions().get("intake");

        assertEquals("10000", definition.params().get("handoff-threshold"));
    }

    @Test
    void missingParamFailsAtLoad() {
        WorkflowProperties.Step condition = step("check", "condition");
        condition.setWhen("slots.amount > params.nope");
        condition.setThen("");
        condition.setOtherwise("");

        assertThrows(IllegalArgumentException.class,
                () -> with(workflow("f", "intake", condition)).toDefinitions());
    }

    @Test
    void emptyConfigProducesEmptyMap() {
        assertTrue(new WorkflowProperties().toDefinitions().isEmpty());
    }
}
