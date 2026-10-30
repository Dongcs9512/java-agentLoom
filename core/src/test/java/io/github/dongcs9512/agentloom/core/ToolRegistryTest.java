package io.github.dongcs9512.agentloom.core;

import io.github.dongcs9512.agentloom.llm.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "d";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("orderId", Map.of("type", "string"));
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                return ToolResult.ok("ok");
            }
        };
    }

    @Test
    void registerAndGet() {
        ToolRegistry registry = new ToolRegistry();
        Tool t = tool("query_order");
        registry.register(t);
        assertTrue(registry.get("query_order").isPresent());
        assertSame(t, registry.get("query_order").get());
    }

    @Test
    void duplicateNameThrows() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("query_order"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(tool("query_order")));
    }

    @Test
    void toToolDefinitionsConverts() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("query_order"));
        List<ToolDefinition> defs = registry.toToolDefinitions();
        assertEquals(1, defs.size());
        assertEquals("query_order", defs.get(0).name());
        assertEquals("d", defs.get(0).description());
        assertEquals(Map.of("orderId", Map.of("type", "string")), defs.get(0).parameters());
    }
}
