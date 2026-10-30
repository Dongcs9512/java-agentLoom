package io.github.dongcs9512.agentloom.config;

import io.github.dongcs9512.agentloom.core.AgentConfig;
import io.github.dongcs9512.agentloom.core.SkillConfig;
import io.github.dongcs9512.agentloom.core.session.Slot;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcConfigRepositoryTest {

    private JdbcConfigRepository repo;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        repo = new JdbcConfigRepository(ds);
    }

    @Test
    void saveAndLoadAgentRoundTrip() {
        repo.saveAgent(new AgentConfig("a1", "prompt", "deepseek-chat", List.of("t1", "t2"), List.of("s1"), 0.5, 100));
        List<AgentConfig> agents = repo.loadAgents();
        assertEquals(1, agents.size());
        AgentConfig a = agents.get(0);
        assertEquals("a1", a.id());
        assertEquals("prompt", a.systemPrompt());
        assertEquals("deepseek-chat", a.model());
        assertEquals(List.of("t1", "t2"), a.toolNames());
        assertEquals(List.of("s1"), a.skillNames());
        assertEquals(0.5, a.temperature());
        assertEquals(100, a.maxTokens());
    }

    @Test
    void saveAndLoadSkillRoundTrip() {
        repo.saveSkill(new SkillConfig("s1", "d", "instructions", List.of("t1")));
        List<SkillConfig> skills = repo.loadSkills();
        assertEquals(1, skills.size());
        assertEquals("s1", skills.get(0).name());
        assertEquals("instructions", skills.get(0).instructions());
        assertEquals(List.of("t1"), skills.get(0).toolNames());
    }

    @Test
    void deleteAgentRemoves() {
        repo.saveAgent(new AgentConfig("a1", "p", "m", List.of(), List.of(), null, null));
        repo.deleteAgent("a1");
        assertTrue(repo.loadAgents().isEmpty());
    }

    @Test
    void slotsRoundTripThroughDatabase() {
        repo.saveAgent(new AgentConfig("a1", "p", "m", List.of(), List.of(), null, null,
                List.of(new Slot("incident", "具体是什么事？", true),
                        new Slot("when", "发生在什么时候？", false))));

        List<Slot> slots = repo.loadAgents().get(0).slots();

        assertEquals(2, slots.size());
        assertEquals("incident", slots.get(0).name());
        assertTrue(slots.get(0).required());
        assertFalse(slots.get(1).required());
    }
}
