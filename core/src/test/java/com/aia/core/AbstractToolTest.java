package com.aia.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractToolTest {

    static class ThrowingTool extends AbstractTool {
        @Override
        public String name() {
            return "t";
        }

        @Override
        public String description() {
            return "d";
        }

        @Override
        protected ToolResult doExecute(ToolInvocation invocation) {
            throw new RuntimeException("boom");
        }
    }

    static class OkTool extends AbstractTool {
        @Override
        public String name() {
            return "t";
        }

        @Override
        public String description() {
            return "d";
        }

        @Override
        protected ToolResult doExecute(ToolInvocation invocation) {
            return ToolResult.ok("ok");
        }
    }

    @Test
    void doExecuteThrowsIsConvertedToError() {
        ToolResult r = new ThrowingTool().execute(new ToolInvocation("c1", "t", Map.of()));
        assertTrue(r.isError());
        assertEquals("boom", r.content());
    }

    @Test
    void doExecuteReturnsIsPassedThrough() {
        ToolResult r = new OkTool().execute(new ToolInvocation("c1", "t", Map.of()));
        assertFalse(r.isError());
        assertEquals("ok", r.content());
    }
}
