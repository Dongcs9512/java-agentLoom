package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.ConfigRepository;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 工作流注册表：数据库优先，配置文件兜底。
 *
 * <p>数据库里的同入口 agent 定义会覆盖 YAML 里的同名定义，这样线上要临时改流程，
 * 写库就行，不用发版重启。为了不让每次请求都查库，加了一层短 TTL 缓存。
 */
@Component
public class WorkflowRegistry {

    private static final Logger LOG = Logger.getLogger(WorkflowRegistry.class.getName());

    private final ConfigRepository configRepository;
    private final Map<String, WorkflowDefinition> fromConfigFile;

    private final boolean loadFromDb;
    private final long cacheTtlMs;

    private volatile Map<String, WorkflowDefinition> fromDatabase = Map.of();
    private volatile long lastLoadedAt;

    public WorkflowRegistry(ConfigRepository configRepository, WorkflowProperties workflowProperties,
                            @Value("${agentloom.workflows.load-from-db:true}") boolean loadFromDb,
                            @Value("${agentloom.workflows.db-cache-ttl-ms:10000}") long cacheTtlMs) {
        this.configRepository = configRepository;
        this.fromConfigFile = workflowProperties.toDefinitions();
        this.loadFromDb = loadFromDb;
        this.cacheTtlMs = cacheTtlMs;
    }

    /** 按入口 agent 找工作流；数据库优先。 */
    public WorkflowDefinition find(String entryAgentId) {
        refreshIfNeeded();
        WorkflowDefinition definition = fromDatabase.get(entryAgentId);
        return definition != null ? definition : fromConfigFile.get(entryAgentId);
    }

    /** 当前生效的全部工作流，键是入口 agent。 */
    public Map<String, WorkflowDefinition> all() {
        refreshIfNeeded();
        Map<String, WorkflowDefinition> merged = new LinkedHashMap<>(fromConfigFile);
        merged.putAll(fromDatabase);
        return merged;
    }

    public void refresh() {
        if (!loadFromDb) {
            return;
        }
        try {
            Map<String, WorkflowDefinition> loaded = new LinkedHashMap<>();
            for (WorkflowDefinition definition : configRepository.loadWorkflows()) {
                loaded.put(definition.entryAgent(), definition);
            }
            fromDatabase = Map.copyOf(loaded);
        } catch (RuntimeException e) {
            // 数据库暂时不可用时继续用上一次的结果或配置文件里的定义
            LOG.log(Level.WARNING, "从数据库加载工作流失败，沿用现有定义: " + e.getMessage());
        } finally {
            lastLoadedAt = System.currentTimeMillis();
        }
    }

    private void refreshIfNeeded() {
        if (!loadFromDb || cacheTtlMs <= 0) {
            refresh();
            return;
        }
        if (System.currentTimeMillis() - lastLoadedAt > cacheTtlMs) {
            refresh();
        }
    }

    /** 供管理接口使用：数据库里存着的工作流。 */
    public Optional<WorkflowDefinition> findInDatabase(String id) {
        return configRepository.loadWorkflows().stream().filter(w -> w.id().equals(id)).findFirst();
    }

    public Map<String, WorkflowDefinition> fromConfigFile() {
        return fromConfigFile;
    }
}
