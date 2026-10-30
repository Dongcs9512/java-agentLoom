package io.github.dongcs9512.agentloom.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultToolResolverTest {

    private static final ToolResolver RESOLVER = new DefaultToolResolver();

    static class FakeTool implements Tool {
        private final String name;

        FakeTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "d-" + name;
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of();
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            return ToolResult.ok("ok");
        }
    }

    private ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FakeTool("order"));
        registry.register(new FakeTool("logistics"));
        return registry;
    }

    private AgentConfig agent(List<String> toolNames) {
        return new AgentConfig("a1", "sys", "m", toolNames, List.of(), null, null);
    }

    private Skill skill(String name, List<Tool> tools) {
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
                return tools;
            }

            @Override
            public Map<String, Object> resources() {
                return Map.of();
            }
        };
    }

    private List<String> names(List<Tool> tools) {
        return tools.stream().map(Tool::name).toList();
    }

    @Test
    void nullToolNamesExposesEverything() {
        assertEquals(List.of("order", "logistics"),
                names(RESOLVER.resolve(agent(null), List.of(), registry())));
    }

    @Test
    void starExposesEverything() {
        assertEquals(List.of("order", "logistics"),
                names(RESOLVER.resolve(agent(List.of("*")), List.of(), registry())));
    }

    @Test
    void emptyToolNamesExposesNoGlobalTools() {
        assertTrue(RESOLVER.resolve(agent(List.of()), List.of(), registry()).isEmpty());
    }

    @Test
    void whitelistFiltersByName() {
        assertEquals(List.of("order"),
                names(RESOLVER.resolve(agent(List.of("order")), List.of(), registry())));
    }

    @Test
    void unknownWhitelistNameIsIgnored() {
        assertEquals(List.of("order"),
                names(RESOLVER.resolve(agent(List.of("order", "missing")), List.of(), registry())));
    }

    @Test
    void skillToolsAreIncludedEvenWhenNotWhitelisted() {
        List<Tool> resolved = RESOLVER.resolve(agent(List.of()), List.of(skill("s1", List.of(new FakeTool("refund")))),
                registry());

        assertEquals(List.of("refund"), names(resolved));
    }

    @Test
    void skillToolIsNotDuplicatedWhenAlreadyVisible() {
        List<Tool> resolved = RESOLVER.resolve(agent(List.of("*")), List.of(skill("s1", List.of(new FakeTool("order")))),
                registry());

        assertEquals(List.of("order", "logistics"), names(resolved));
    }
}
