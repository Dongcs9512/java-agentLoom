package io.github.dongcs9512.agentloom.session.jdbc;

import io.github.dongcs9512.agentloom.core.session.AnswerCache;
import io.github.dongcs9512.agentloom.core.session.QuestionNormalizer;
import io.github.dongcs9512.agentloom.session.CacheKey;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * 热点问题答案的数据库存储，作为 Redis 缓存的归档与回源。
 */
public final class JdbcAnswerCache implements AnswerCache {

    private final DataSource dataSource;

    public JdbcAnswerCache(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    private void initSchema() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS answer_cache (" +
                    "agent_id VARCHAR(64), question_key VARCHAR(128), question TEXT, answer TEXT, " +
                    "hit_count INT DEFAULT 0, updated_at BIGINT, PRIMARY KEY (agent_id, question_key))");
        } catch (SQLException e) {
            throw new IllegalStateException("初始化答案缓存表失败", e);
        }
    }

    @Override
    public Optional<String> get(String agentId, String question) {
        String normalized = QuestionNormalizer.normalize(question);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT answer FROM answer_cache WHERE agent_id = ? AND question_key = ?")) {
            ps.setString(1, agentId == null ? "" : agentId);
            ps.setString(2, CacheKey.of(agentId, question));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString("answer")) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取答案缓存失败", e);
        }
    }

    @Override
    public void put(String agentId, String question, String answer) {
        String normalized = QuestionNormalizer.normalize(question);
        if (normalized.isEmpty() || answer == null || answer.isBlank()) {
            return;
        }
        String agent = agentId == null ? "" : agentId;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM answer_cache WHERE agent_id = ? AND question_key = ?")) {
                del.setString(1, agent);
                del.setString(2, CacheKey.of(agentId, question));
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO answer_cache (agent_id, question_key, question, answer, hit_count, updated_at) "
                            + "VALUES (?, ?, ?, ?, 0, ?)")) {
                ins.setString(1, agent);
                ins.setString(2, CacheKey.of(agentId, question));
                ins.setString(3, question);
                ins.setString(4, answer);
                ins.setLong(5, System.currentTimeMillis());
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入答案缓存失败", e);
        }
    }
}
