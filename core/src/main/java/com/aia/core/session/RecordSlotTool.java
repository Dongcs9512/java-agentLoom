package com.aia.core.session;

import com.aia.core.AbstractTool;
import com.aia.core.ToolInvocation;
import com.aia.core.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 让模型把从对话中收集到的信息写回会话。每次调用返回当前缺失项，模型据此决定继续追问还是给结论。
 */
public final class RecordSlotTool extends AbstractTool {

    public static final String NAME = "record_case_info";

    private final Session session;
    private final List<Slot> slots;

    public RecordSlotTool(Session session, List<Slot> slots) {
        this.session = session;
        this.slots = slots == null ? List.of() : List.copyOf(slots);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "记录已经从用户那里了解到的信息。每次获得一项信息就调用一次，收集齐之前不要给最终结论。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> name = new LinkedHashMap<>();
        name.put("type", "string");
        name.put("description", "信息项名称，可选值: " + String.join(", ", slots.stream().map(Slot::name).toList()));

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "string");
        value.put("description", "用户提供的具体内容，原话概括即可");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", name);
        properties.put("value", value);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name", "value"));
        return schema;
    }

    @Override
    protected ToolResult doExecute(ToolInvocation invocation) {
        Object rawName = invocation.arguments().get("name");
        Object rawValue = invocation.arguments().get("value");
        if (rawName == null || String.valueOf(rawName).isBlank()) {
            return ToolResult.error("缺少必填参数: name");
        }
        if (rawValue == null || String.valueOf(rawValue).isBlank()) {
            return ToolResult.error("缺少必填参数: value");
        }

        String slotName = String.valueOf(rawName).trim();
        Optional<Slot> slot = slots.stream().filter(s -> s.name().equals(slotName)).findFirst();
        if (slot.isEmpty()) {
            return ToolResult.error("未知的信息项: " + slotName + "，可选: "
                    + String.join(", ", slots.stream().map(Slot::name).toList()));
        }

        session.putSlot(slotName, String.valueOf(rawValue).trim());

        String status = SlotPromptBuilder.statusOf(slots, session);
        List<Slot> missingRequired = SlotPromptBuilder.missing(slots, session).stream()
                .filter(Slot::required)
                .toList();
        if (missingRequired.isEmpty()) {
            return ToolResult.ok("已记录 " + slotName + "。" + status + "。必填信息已收集齐，可以给出分析和建议了。");
        }
        return ToolResult.ok("已记录 " + slotName + "。" + status
                + "。还差 " + missingRequired.size() + " 项必填信息，请继续询问。");
    }
}
