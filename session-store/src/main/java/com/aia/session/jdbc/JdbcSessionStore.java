package com.aia.session.jdbc;

import com.aia.core.session.Session;
import com.aia.core.session.SessionStore;
import com.aia.llm.ChatMessage;
import com.aia.session.SessionSerializer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 会话归档存储：会话主表 + 消息明细表，便于按会话查询历史记录。
 * 建表在构造时幂等完成。
 */
public final class JdbcSessionStore implements SessionStore {

    private final DataSource dataSource;

    public JdbcSessionStore(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    private void initSchema() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS chat_session (" +
                    "id VARCHAR(64) PRIMARY KEY, agent_id VARCHAR(64), summary TEXT, " +
                    "slots TEXT, updated_at BIGINT)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_message (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(64), seq INT, payload TEXT)");
        } catch (SQLException e) {
            throw new IllegalStateException("初始化会话表失败", e);
        }
    }

    @Override
    public void save(Session session) {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM chat_session WHERE id = ?")) {
                del.setString(1, session.id());
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO chat_session (id, agent_id, summary, slots, updated_at) VALUES (?, ?, ?, ?, ?)")) {
                ins.setString(1, session.id());
                ins.setString(2, session.agentId());
                ins.setString(3, session.summary());
                ins.setString(4, SessionSerializer.slotsToJson(session.slots()));
                ins.setLong(5, session.updatedAt());
                ins.executeUpdate();
            }

            try (PreparedStatement del = c.prepareStatement("DELETE FROM chat_message WHERE session_id = ?")) {
                del.setString(1, session.id());
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO chat_message (session_id, seq, payload) VALUES (?, ?, ?)")) {
                List<ChatMessage> messages = session.messages();
                for (int i = 0; i < messages.size(); i++) {
                    ins.setString(1, session.id());
                    ins.setInt(2, i);
                    ins.setString(3, SessionSerializer.messageToJson(messages.get(i)));
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("保存会话失败", e);
        }
    }

    @Override
    public Optional<Session> load(String sessionId) {
        try (Connection c = dataSource.getConnection()) {
            String summary = null;
            String agentId = null;
            String slotsJson = null;
            long updatedAt = 0;
            boolean found = false;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT agent_id, summary, slots, updated_at FROM chat_session WHERE id = ?")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        found = true;
                        agentId = rs.getString("agent_id");
                        summary = rs.getString("summary");
                        slotsJson = rs.getString("slots");
                        updatedAt = rs.getLong("updated_at");
                    }
                }
            }
            if (!found) {
                return Optional.empty();
            }

            List<ChatMessage> messages = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT payload FROM chat_message WHERE session_id = ? ORDER BY seq")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        messages.add(SessionSerializer.messageFromJson(rs.getString("payload")));
                    }
                }
            }
            return Optional.of(new Session(sessionId, agentId, messages, summary,
                    SessionSerializer.slotsFromJson(slotsJson), updatedAt));
        } catch (SQLException e) {
            throw new IllegalStateException("加载会话失败", e);
        }
    }

    @Override
    public void delete(String sessionId) {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM chat_message WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM chat_session WHERE id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("删除会话失败", e);
        }
    }
}
