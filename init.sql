-- ============================================================
-- AgentLoom 数据库初始化脚本（MySQL 8.0+）
--
-- 应用启动时会自动 CREATE TABLE IF NOT EXISTS 建表，本脚本用于：
--   1) 生产环境由 DBA 统一执行，避免应用账号需要 DDL 权限
--   2) 新建环境时一次性建好库和表
--
-- 执行：mysql -u root -p < init.sql
-- 重复执行安全：全部使用 IF NOT EXISTS；索引重复创建会报错，可忽略
-- ============================================================

CREATE DATABASE IF NOT EXISTS ai_agent
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE ai_agent;

-- ------------------------------------------------------------
-- 1. 配置：agent / skill / 工作流
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS agent_config (
    id            VARCHAR(64) PRIMARY KEY COMMENT 'agent 标识',
    system_prompt TEXT        COMMENT '系统提示词',
    model         VARCHAR(128) COMMENT '模型名',
    tool_names    TEXT        COMMENT '可用工具，JSON 数组；["*"] 表示全部，[] 表示零工具',
    skill_names   TEXT        COMMENT '挂载的技能，JSON 数组',
    temperature   DOUBLE      COMMENT '采样温度',
    max_tokens    INT         COMMENT '最大输出 token',
    slots         TEXT        COMMENT '需要收集的信息项，JSON 数组 [{name,ask,required}]',
    KEY idx_agent_model (model)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'agent 配置';

CREATE TABLE IF NOT EXISTS skill_config (
    name         VARCHAR(64) PRIMARY KEY COMMENT '技能名',
    description  VARCHAR(255) COMMENT '一句话描述',
    instructions TEXT         COMMENT '技能指令，会拼进系统提示词',
    tool_names   TEXT         COMMENT '技能自带的工具，JSON 数组'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'skill 配置';

CREATE TABLE IF NOT EXISTS workflow_config (
    id          VARCHAR(64) PRIMARY KEY COMMENT '工作流 id',
    entry_agent VARCHAR(64) NOT NULL    COMMENT '入口 agent，按它匹配请求',
    steps       TEXT        NOT NULL    COMMENT '步骤定义，JSON 数组',
    enabled     TINYINT     DEFAULT 1   COMMENT '1 启用 / 0 停用',
    updated_at  BIGINT                  COMMENT '最后修改时间（毫秒）',
    KEY idx_workflow_entry (entry_agent)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工作流定义';

-- ------------------------------------------------------------
-- 2. 会话（aia-session-store 的 JDBC 实现使用）
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS chat_session (
    id         VARCHAR(64) PRIMARY KEY COMMENT '会话 id',
    agent_id   VARCHAR(64) COMMENT '所属 agent',
    summary    TEXT        COMMENT '上下文压缩后的摘要',
    slots      TEXT        COMMENT '已收集的槽位，JSON 对象',
    updated_at BIGINT      COMMENT '最后活跃时间（毫秒）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话主表';

CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) COMMENT '会话 id',
    seq        INT         COMMENT '会话内序号',
    payload    TEXT        COMMENT '单条消息，JSON',
    KEY idx_message_session (session_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话消息明细';

-- ------------------------------------------------------------
-- 3. 热点问题答案缓存（aia-session-store 的 JDBC 实现使用）
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS answer_cache (
    agent_id     VARCHAR(64)  NOT NULL COMMENT '所属 agent，空串表示不限',
    question_key VARCHAR(128) NOT NULL COMMENT '归一化问题的 SHA-256',
    question     TEXT         COMMENT '原始问题，便于排查',
    answer       TEXT         COMMENT '缓存回答',
    hit_count    INT DEFAULT 0 COMMENT '命中次数',
    updated_at   BIGINT       COMMENT '写入时间（毫秒）',
    PRIMARY KEY (agent_id, question_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '答案缓存';

-- ------------------------------------------------------------
-- 4. 可选：把工作流从配置文件搬进数据库
--    （应用里 agentloom.workflows.load-from-db=true 时生效，数据库优先于 YAML）
-- ------------------------------------------------------------
-- INSERT INTO workflow_config (id, entry_agent, steps, enabled, updated_at) VALUES (
--   'legal-intake-flow',
--   'legal-intake',
--   '[{"id":"collect","type":"COLLECT","slots":["incident","when","location"],"maxTurns":5},
--     {"id":"dispatch","type":"DISPATCH"},
--     {"id":"answer","type":"CALL","agent":"@routed"}]',
--   1,
--   UNIX_TIMESTAMP() * 1000
-- );
