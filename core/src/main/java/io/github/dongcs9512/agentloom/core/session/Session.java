package io.github.dongcs9512.agentloom.core.session;

import io.github.dongcs9512.agentloom.llm.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次对话会话：消息历史 + 压缩后的摘要 + 已收集的槽位信息。
 * 可变对象，由 {@link SessionStore} 负责持久化。
 */
public final class Session {

    private final String id;
    private final String agentId;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final Map<String, Object> slots = new LinkedHashMap<>();
    private String summary;
    /** 当前接待者：ai:<agentId> 或 human:<坐席名>；为空表示沿用默认（AI）。 */
    private String operator;
    private long updatedAt;

    public Session(String id, String agentId) {
        this.id = id;
        this.agentId = agentId;
        this.updatedAt = System.currentTimeMillis();
    }

    public Session(String id, String agentId, List<ChatMessage> messages, String summary,
                   Map<String, Object> slots, long updatedAt) {
        this(id, agentId, messages, summary, slots, updatedAt, null);
    }

    public Session(String id, String agentId, List<ChatMessage> messages, String summary,
                   Map<String, Object> slots, long updatedAt, String operator) {
        this.id = id;
        this.agentId = agentId;
        if (messages != null) {
            this.messages.addAll(messages);
        }
        if (slots != null) {
            this.slots.putAll(slots);
        }
        this.summary = summary;
        this.operator = operator;
        this.updatedAt = updatedAt > 0 ? updatedAt : System.currentTimeMillis();
    }

    public String id() {
        return id;
    }

    public String agentId() {
        return agentId;
    }

    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    public String summary() {
        return summary;
    }

    public Map<String, Object> slots() {
        return Collections.unmodifiableMap(slots);
    }

    public long updatedAt() {
        return updatedAt;
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        touch();
    }

    public void addMessages(List<ChatMessage> newMessages) {
        messages.addAll(newMessages);
        touch();
    }

    public void putSlot(String name, Object value) {
        slots.put(name, value);
        touch();
    }

    /**
     * 用摘要 + 保留下来的消息替换整个历史（上下文压缩后调用）。
     */
    public void replaceHistory(String newSummary, List<ChatMessage> keptMessages) {
        // 先拷贝：keptMessages 可能是本对象 messages 的视图，直接 clear 会把它清空
        List<ChatMessage> kept = keptMessages == null ? List.of() : List.copyOf(keptMessages);
        this.summary = newSummary;
        this.messages.clear();
        this.messages.addAll(kept);
        touch();
    }

    /**
     * @return 槽位的字符串值，未收集时返回空串
     */
    public String slot(String name) {
        Object value = slots.get(name);
        return value == null ? "" : String.valueOf(value);
    }

    public String operator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
        touch();
    }

    /** 当前是否由人工接待。 */
    public boolean handledByHuman() {
        return operator != null && operator.startsWith("human:");
    }

    public Session copy() {
        return new Session(id, agentId, messages, summary, slots, updatedAt, operator);
    }

    private void touch() {
        this.updatedAt = System.currentTimeMillis();
    }
}
