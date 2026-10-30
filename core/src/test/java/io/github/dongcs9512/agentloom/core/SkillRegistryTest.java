package io.github.dongcs9512.agentloom.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryTest {

    private Skill skill(String name) {
        return new Skill() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "d";
            }

            @Override
            public String instructions() {
                return "i";
            }

            @Override
            public List<Tool> tools() {
                return List.of();
            }

            @Override
            public Map<String, Object> resources() {
                return Map.of();
            }
        };
    }

    @Test
    void registerAndGet() {
        SkillRegistry registry = new SkillRegistry();
        Skill s = skill("logistics");
        registry.register(s);
        assertTrue(registry.get("logistics").isPresent());
        assertSame(s, registry.get("logistics").get());
    }

    @Test
    void duplicateNameThrows() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(skill("logistics"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(skill("logistics")));
    }

    @Test
    void allReturnsInsertionOrder() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(skill("a"));
        registry.register(skill("b"));
        assertEquals(List.of("a", "b"), registry.all().stream().map(Skill::name).toList());
    }
}
