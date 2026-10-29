package com.aia.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryConfigRepositoryTest {

    private AgentConfig agent(String id) {
        return new AgentConfig(id, "prompt", "deepseek-chat", List.of("t1"), List.of("s1"), 0.5, 100);
    }

    @Test
    void saveAndLoadAgents() {
        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        repo.saveAgent(agent("a1"));
        assertEquals(1, repo.loadAgents().size());
        assertEquals("a1", repo.loadAgents().get(0).id());
    }

    @Test
    void saveAgentRejectsEmptyId() {
        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        assertThrows(IllegalArgumentException.class, () -> repo.saveAgent(agent("")));
    }

    @Test
    void saveAndDeleteSkill() {
        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        repo.saveSkill(new SkillConfig("s1", "d", "i", List.of("t1")));
        assertEquals(1, repo.loadSkills().size());
        repo.deleteSkill("s1");
        assertTrue(repo.loadSkills().isEmpty());
    }
}
