package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.ConfigRepository;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowDefinition;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流管理接口：写进配置仓库（jdbc 模式下就是数据库），保存后立刻生效，不用重启。
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final ConfigRepository configRepository;
    private final WorkflowRegistry registry;

    public WorkflowController(ConfigRepository configRepository, WorkflowRegistry registry) {
        this.configRepository = configRepository;
        this.registry = registry;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        // 先查一次库，避免每个条目都打一次数据库
        java.util.Set<String> databaseEntries = configRepository.loadWorkflows().stream()
                .map(WorkflowDefinition::entryAgent)
                .collect(java.util.stream.Collectors.toSet());

        List<Map<String, Object>> items = new ArrayList<>();
        registry.all().forEach((entryAgent, definition) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", definition.id());
            item.put("entryAgent", entryAgent);
            item.put("steps", definition.steps());
            item.put("source", databaseEntries.contains(entryAgent) ? "database" : "config-file");
            items.add(item);
        });
        return items;
    }

    @PostMapping
    public Map<String, Object> save(@RequestBody WorkflowDefinition definition) {
        // 保存前先校验：条件能解析、跳转目标存在
        definition.validate();
        configRepository.saveWorkflow(definition);
        registry.refresh();
        return Map.of("id", definition.id(), "entryAgent", definition.entryAgent(), "saved", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        configRepository.deleteWorkflow(id);
        registry.refresh();
        return Map.of("id", id, "deleted", true);
    }
}
