package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.workflow.StepType;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowDefinition;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowStep;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 {@code agentloom.workflows} 读取工作流定义。
 *
 * <p>第一阶段用配置文件承载；后续接数据库时，只要产出同样的
 * {@link WorkflowDefinition}，引擎和运行时都不用改。
 */
@Component
@ConfigurationProperties(prefix = "agentloom.workflows")
public class WorkflowProperties {

    private List<Workflow> items = new ArrayList<>();

    public List<Workflow> getItems() {
        return items;
    }

    public void setItems(List<Workflow> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }

    /** 按入口 agent 建索引，运行时按 agentId 直接查。 */
    public Map<String, WorkflowDefinition> toDefinitions() {
        Map<String, WorkflowDefinition> byEntryAgent = new LinkedHashMap<>();
        for (Workflow workflow : items) {
            WorkflowDefinition definition = workflow.toDefinition();
            definition.validate();
            byEntryAgent.put(definition.entryAgent(), definition);
        }
        return byEntryAgent;
    }

    public static class Workflow {
        private String id;
        private String entryAgent;
        private Map<String, String> params = new LinkedHashMap<>();
        private List<Step> steps = new ArrayList<>();

        public Map<String, String> getParams() {
            return params;
        }

        public void setParams(Map<String, String> params) {
            this.params = params == null ? new LinkedHashMap<>() : params;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getEntryAgent() {
            return entryAgent;
        }

        public void setEntryAgent(String entryAgent) {
            this.entryAgent = entryAgent;
        }

        public List<Step> getSteps() {
            return steps;
        }

        public void setSteps(List<Step> steps) {
            this.steps = steps == null ? new ArrayList<>() : steps;
        }

        WorkflowDefinition toDefinition() {
            String workflowId = id == null || id.isBlank() ? entryAgent : id;
            if (entryAgent == null || entryAgent.isBlank()) {
                throw new IllegalArgumentException("工作流 " + workflowId + " 缺少 entry-agent");
            }
            List<WorkflowStep> converted = new ArrayList<>();
            for (Step step : steps) {
                converted.add(step.toStep());
            }
            return new WorkflowDefinition(workflowId, entryAgent, params, converted);
        }
    }

    public static class Step {
        private String id;
        private String type;
        private List<String> slots = new ArrayList<>();
        private Integer maxTurns;
        private String strategy;
        private String agent;
        private String when;
        private String then;
        private String otherwise;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public List<String> getSlots() {
            return slots;
        }

        public void setSlots(List<String> slots) {
            this.slots = slots == null ? new ArrayList<>() : slots;
        }

        public Integer getMaxTurns() {
            return maxTurns;
        }

        public void setMaxTurns(Integer maxTurns) {
            this.maxTurns = maxTurns;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public String getAgent() {
            return agent;
        }

        public void setAgent(String agent) {
            this.agent = agent;
        }

        public String getWhen() {
            return when;
        }

        public void setWhen(String when) {
            this.when = when;
        }

        public String getThen() {
            return then;
        }

        public void setThen(String then) {
            this.then = then;
        }

        public String getOtherwise() {
            return otherwise;
        }

        public void setOtherwise(String otherwise) {
            this.otherwise = otherwise;
        }

        WorkflowStep toStep() {
            StepType stepType;
            try {
                stepType = StepType.valueOf(type == null ? "" : type.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("工作流步骤 " + id + " 的 type 无法识别: " + type
                        + "，可选 collect / dispatch / call / condition");
            }
            String stepId = id == null || id.isBlank() ? stepType.name().toLowerCase(Locale.ROOT) : id;
            return new WorkflowStep(stepId, stepType, slots, maxTurns, strategy, agent, when, then, otherwise);
        }
    }
}
