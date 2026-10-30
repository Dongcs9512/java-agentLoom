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
        List<Slot> missingRequired = new ArrayList<>();
        List<Slot> missingOptional = new ArrayList<>();
        for (Slot slot : missing) {
            (slot.required() ? missingRequired : missingOptional).add(slot);
        }

        if (!missingRequired.isEmpty()) {
            sb.append("\n还缺下列必填信息，请先用一句话向用户询问（一次只问一到两项，不要一次全抛出来）：");
            for (Slot slot : missingRequired) {
                sb.append("\n- ").append(slot.name()).append("：").append(slot.ask());
            }
            sb.append("\n用户回答后必须调用 record_case_info 记录，必填信息收集齐之前不要下结论。");
        } else if (!missingOptional.isEmpty()) {
            // 只剩可选信息时不能继续追问，否则会卡在收集阶段给不出结论
            sb.append("\n必填信息已收集完整，可以直接结合知识与已收集的信息给出分析和建议。");
            sb.append("\n可选信息（");
            sb.append(String.join("、", missingOptional.stream().map(Slot::name).toList()));
            sb.append("）如果用户没有主动提供，不必再追问。");
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
