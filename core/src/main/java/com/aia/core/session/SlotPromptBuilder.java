package com.aia.core.session;

import java.util.ArrayList;
import java.util.List;

/**
 * 把槽位状态拼进系统提示词，并在收集完成后提示可以给结论。
 */
public final class SlotPromptBuilder {

    private SlotPromptBuilder() {
    }

    public static List<Slot> missing(List<Slot> slots, Session session) {
        List<Slot> missing = new ArrayList<>();
        for (Slot slot : slots) {
            if (session.slot(slot.name()).isBlank()) {
                missing.add(slot);
            }
        }
        return missing;
    }

    public static String build(List<Slot> slots, Session session) {
        if (slots == null || slots.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【信息收集】");

        List<String> collected = new ArrayList<>();
        for (Slot slot : slots) {
            String value = session.slot(slot.name());
            if (!value.isBlank()) {
                collected.add(slot.name() + "=" + value);
            }
        }
        if (!collected.isEmpty()) {
            sb.append("\n已收集：").append(String.join("；", collected));
        }

        List<Slot> missing = missing(slots, session);
        if (!missing.isEmpty()) {
            sb.append("\n还缺下列信息，请先用一句话向用户询问（一次只问一到两项，不要一次全抛出来）：");
            for (Slot slot : missing) {
                sb.append("\n- ").append(slot.name()).append("：").append(slot.ask());
                if (!slot.required()) {
                    sb.append("（可选）");
                }
            }
            sb.append("\n用户回答后必须调用 record_case_info 记录，收集齐之前不要下结论。");
        } else {
            sb.append("\n信息已收集完整，可以结合知识与已收集信息给出分析和建议。");
        }
        return sb.toString();
    }

    /**
     * 工具返回给模型的一行状态，告诉它当前还缺什么。
     */
    public static String statusOf(List<Slot> slots, Session session) {
        List<String> collected = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Slot slot : slots) {
            String value = session.slot(slot.name());
            if (value.isBlank()) {
                missing.add(slot.name());
            } else {
                collected.add(slot.name() + "=" + value);
            }
        }
        StringBuilder sb = new StringBuilder("已收集: ")
                .append(collected.isEmpty() ? "无" : String.join("、", collected));
        sb.append("；待收集: ").append(missing.isEmpty() ? "无" : String.join("、", missing));
        return sb.toString();
    }
}
