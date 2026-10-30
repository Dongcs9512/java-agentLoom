package com.aia.core.session;

import com.aia.core.ToolInvocation;
import com.aia.core.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordSlotToolTest {

    private static final List<Slot> SLOTS = List.of(
            new Slot("incident", "具体是什么事？", true),
            new Slot("when", "事情发生在什么时候？", true));

    private RecordSlotTool tool(Session session) {
        return new RecordSlotTool(session, SLOTS);
    }

    private ToolInvocation invoke(Map<String, Object> args) {
        return new ToolInvocation("c1", "record_case_info", args);
    }

    @Test
    void recordsValueIntoSession() {
        Session session = new Session("s1", "a1");

        ToolResult result = tool(session).execute(invoke(Map.of("name", "incident", "value", "被公司辞退")));

        assertFalse(result.isError());
        assertEquals("被公司辞退", session.slot("incident"));
    }

    @Test
    void resultEchoesRemainingSlots() {
        Session session = new Session("s1", "a1");

        ToolResult result = tool(session).execute(invoke(Map.of("name", "incident", "value", "被公司辞退")));

        assertTrue(result.content().contains("when"), result.content());
        assertTrue(result.content().contains("还差"), result.content());
    }

    @Test
    void unknownSlotNameIsRejected() {
        Session session = new Session("s1", "a1");

        ToolResult result = tool(session).execute(invoke(Map.of("name", "salary", "value", "8000")));

        assertTrue(result.isError());
        assertTrue(result.content().contains("incident"), result.content());
    }

    @Test
    void missingValueIsRejected() {
        ToolResult result = tool(new Session("s1", "a1")).execute(invoke(Map.of("name", "incident")));

        assertTrue(result.isError());
        assertTrue(result.content().contains("value"), result.content());
    }

    @Test
    void allCollectedReportsCompletion() {
        Session session = new Session("s1", "a1");
        session.putSlot("incident", "被辞退");

        ToolResult result = tool(session).execute(invoke(Map.of("name", "when", "value", "上周五")));

        assertTrue(result.content().contains("已收集齐"), result.content());
    }

    @Test
    void exposesOpenAiSchema() {
        RecordSlotTool tool = tool(new Session("s1", "a1"));

        assertEquals("record_case_info", tool.name());
        assertEquals("object", tool.parameters().get("type"));
        assertEquals(List.of("name", "value"), tool.parameters().get("required"));
    }
}
