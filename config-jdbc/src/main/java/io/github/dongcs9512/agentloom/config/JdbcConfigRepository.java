package io.github.dongcs9512.agentloom.config;

import io.github.dongcs9512.agentloom.core.AgentConfig;
import io.github.dongcs9512.agentloom.core.ConfigRepository;
import io.github.dongcs9512.agentloom.core.SkillConfig;
import io.github.dongcs9512.agentloom.core.session.Slot;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowDefinition;
import io.github.dongcs9512.agentloom.core.workflow.WorkflowStep;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public final class JdbcConfigRepository implements ConfigRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Slot>> SLOT_LIST = new TypeReference<>() {};
    private static final TypeReference<List<WorkflowStep>> STEP_LIST = new TypeReference<>() {};

    private final DataSource dataSource;

    public JdbcConfigRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    private void initSchema() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS agent_config (" +
                    "id VARCHAR(64) PRIMARY KEY, system_prompt TEXT, model VARCHAR(128), " +
                    "tool_names TEXT, skill_names TEXT, temperature DOUBLE, max_tokens INT, slots TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS skill_config (" +
                    "name VARCHAR(64) PRIMARY KEY, description VARCHAR(255), instructions TEXT, tool_names TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS workflow_config (" +
                    "id VARCHAR(64) PRIMARY KEY, entry_agent VARCHAR(64), steps TEXT, " +
                    "enabled TINYINT DEFAULT 1, updated_at BIGINT)");
            // 老库没有 slots 列时补上；已存在则忽略
            try (Statement alter = c.createStatement()) {
                alter.execute("ALTER TABLE agent_config ADD COLUMN slots TEXT");
            } catch (SQLException ignored) {
                // 列已存在
            }
        } catch (SQLException e) {
            throw new IllegalStateException("初始化配置表失败", e);
        }
    }

    @Override
    public List<AgentConfig> loadAgents() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM agent_config")) {
            List<AgentConfig> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new AgentConfig(
                        rs.getString("id"),
                        rs.getString("system_prompt"),
                        rs.getString("model"),
                        parseList(rs.getString("tool_names")),
                        parseList(rs.getString("skill_names")),
                        rs.getObject("temperature") == null ? null : rs.getDouble("temperature"),
                        rs.getObject("max_tokens") == null ? null : rs.getInt("max_tokens"),
                        parseSlots(rs.getString("slots"))));
            }
            return list;
        } catch (SQLException e) {
            throw new IllegalStateException("加载 agent 配置失败", e);
        }
    }

    @Override
    public List<SkillConfig> loadSkills() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM skill_config")) {
            List<SkillConfig> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new SkillConfig(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("instructions"),
                        parseList(rs.getString("tool_names"))));
            }
            return list;
        } catch (SQLException e) {
            throw new IllegalStateException("加载 skill 配置失败", e);
        }
    }

    @Override
    public void saveAgent(AgentConfig config) {
        if (config.id() == null || config.id().isBlank()) {
            throw new IllegalArgumentException("agent id 不能为空");
        }
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM agent_config WHERE id = ?")) {
                del.setString(1, config.id());
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO agent_config (id, system_prompt, model, tool_names, skill_names, temperature, max_tokens, slots) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ins.setString(1, config.id());
                ins.setString(2, config.systemPrompt());
                ins.setString(3, config.model());
                ins.setString(4, toJson(config.toolNames()));
                ins.setString(5, toJson(config.skillNames()));
                if (config.temperature() == null) {
                    ins.setNull(6, Types.DOUBLE);
                } else {
                    ins.setDouble(6, config.temperature());
                }
                if (config.maxTokens() == null) {
                    ins.setNull(7, Types.INTEGER);
                } else {
                    ins.setInt(7, config.maxTokens());
                }
                ins.setString(8, toSlotsJson(config.slots()));
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存 agent 配置失败", e);
        }
    }

    @Override
    public void deleteAgent(String id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM agent_config WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除 agent 配置失败", e);
        }
    }

    @Override
    public void saveSkill(SkillConfig config) {
        if (config.name() == null || config.name().isBlank()) {
            throw new IllegalArgumentException("skill name 不能为空");
        }
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM skill_config WHERE name = ?")) {
                del.setString(1, config.name());
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO skill_config (name, description, instructions, tool_names) VALUES (?, ?, ?, ?)")) {
                ins.setString(1, config.name());
                ins.setString(2, config.description());
                ins.setString(3, config.instructions());
                ins.setString(4, toJson(config.toolNames()));
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存 skill 配置失败", e);
        }
    }

    @Override
    public void deleteSkill(String name) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM skill_config WHERE name = ?")) {
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除 skill 配置失败", e);
        }
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException("序列化列表失败", e);
        }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toSlotsJson(List<Slot> slots) {
        if (slots == null || slots.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(slots);
        } catch (Exception e) {
            throw new IllegalStateException("序列化槽位失败", e);
        }
    }

    private List<Slot> parseSlots(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, SLOT_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<WorkflowDefinition> loadWorkflows() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, entry_agent, steps FROM workflow_config WHERE enabled = 1")) {
            List<WorkflowDefinition> list = new ArrayList<>();
            while (rs.next()) {
                list.add(new WorkflowDefinition(
                        rs.getString("id"),
                        rs.getString("entry_agent"),
                        parseSteps(rs.getString("steps"))));
            }
            return list;
        } catch (SQLException e) {
            throw new IllegalStateException("加载工作流配置失败", e);
        }
    }

    @Override
    public void saveWorkflow(WorkflowDefinition workflow) {
        if (workflow.id() == null || workflow.id().isBlank()) {
            throw new IllegalArgumentException("工作流 id 不能为空");
        }
        // 存之前先校验，别把写错的条件推进库里
        workflow.validate();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM workflow_config WHERE id = ?")) {
                del.setString(1, workflow.id());
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO workflow_config (id, entry_agent, steps, enabled, updated_at) VALUES (?, ?, ?, 1, ?)")) {
                ins.setString(1, workflow.id());
                ins.setString(2, workflow.entryAgent());
                ins.setString(3, toStepsJson(workflow.steps()));
                ins.setLong(4, System.currentTimeMillis());
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存工作流配置失败", e);
        }
    }

    @Override
    public void deleteWorkflow(String id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM workflow_config WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除工作流配置失败", e);
        }
    }

    private String toStepsJson(List<WorkflowStep> steps) {
        try {
            return MAPPER.writeValueAsString(steps);
        } catch (Exception e) {
            throw new IllegalStateException("序列化工作流步骤失败", e);
        }
    }

    private List<WorkflowStep> parseSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, STEP_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("解析工作流步骤失败: " + e.getMessage(), e);
        }
    }
}
