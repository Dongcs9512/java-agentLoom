package io.github.dongcs9512.agentloom.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolScannerTest {

    static class OrderTools {
        @ToolDef(name = "query_order", description = "根据订单号查询订单")
        public String queryOrder(@ToolParam(description = "订单号", required = true) String orderId,
                                 @ToolParam(description = "详情", required = false) boolean detail) {
            return "order-" + orderId;
        }

        @ToolDef(name = "boom", description = "抛异常")
        public String boom() {
            throw new IllegalStateException("failed");
        }
    }

    private Tool find(List<Tool> tools, String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void scansNameDescriptionAndSchema() {
        List<Tool> tools = new ToolScanner().scan(new OrderTools());
        assertEquals(2, tools.size());

        Tool queryOrder = find(tools, "query_order");
        assertEquals("根据订单号查询订单", queryOrder.description());

        Map<String, Object> params = queryOrder.parameters();
        assertEquals("object", params.get("type"));
        Map<String, Object> props = (Map<String, Object>) params.get("properties");
        assertNotNull(props);
        assertTrue(props.containsKey("orderId"));
        Map<String, Object> orderId = (Map<String, Object>) props.get("orderId");
        assertEquals("string", orderId.get("type"));
        assertEquals("订单号", orderId.get("description"));
        assertTrue(((List<?>) params.get("required")).contains("orderId"));
        assertFalse(((List<?>) params.get("required")).contains("detail"));
    }

    @Test
    void executeInvokesMethod() {
        Tool queryOrder = find(new ToolScanner().scan(new OrderTools()), "query_order");
        ToolResult r = queryOrder.execute(new ToolInvocation("c1", "query_order", Map.of("orderId", "123", "detail", true)));
        assertFalse(r.isError());
        assertEquals("order-123", r.content());
    }

    @Test
    void executeConvertsExceptionToError() {
        Tool boom = find(new ToolScanner().scan(new OrderTools()), "boom");
        ToolResult r = boom.execute(new ToolInvocation("c1", "boom", Map.of()));
        assertTrue(r.isError());
        assertEquals("failed", r.content());
    }

    @Test
    void missingRequiredParamReturnsError() {
        Tool queryOrder = find(new ToolScanner().scan(new OrderTools()), "query_order");
        ToolResult r = queryOrder.execute(new ToolInvocation("c1", "query_order", Map.of()));
        assertTrue(r.isError());
    }
}
