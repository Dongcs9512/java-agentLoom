package io.github.dongcs9512.agentloom.session;

import io.github.dongcs9512.agentloom.llm.ChatMessage;
import io.github.dongcs9512.agentloom.llm.ToolCall;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话内容的 JSON 序列化。读的时候把缺失的 toolCalls 归一成空列表，
 * 避免老数据反序列化出 null 后在使用处炸掉。
 */
public final class SessionSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ChatMessage>> MESSAGE_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private SessionSerializer() {
    }

    public static String messagesToJson(List<ChatMessage> messages) {
        try {
            return MAPPER.writeValueAsString(messages == null ? List.of() : messages);
        } catch (Exception e) {
            throw new IllegalStateException("消息序列化失败: " + e.getMessage(), e);
        }
    }

    public static List<ChatMessage> messagesFromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<ChatMessage> messages = MAPPER.readValue(json, MESSAGE_LIST);
            List<ChatMessage> normalized = new ArrayList<>(messages.size());
            for (ChatMessage message : messages) {
                normalized.add(message.toolCalls() == null
                        ? new ChatMessage(message.role(), message.content(), List.of(), message.toolCallId())
                        : message);
            }
            return normalized;
        } catch (Exception e) {
            throw new IllegalStateException("消息反序列化失败: " + e.getMessage(), e);
        }
    }

    public static String messageToJson(ChatMessage message) {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("单条消息序列化失败: " + e.getMessage(), e);
        }
    }

    public static ChatMessage messageFromJson(String json) {
        List<ChatMessage> messages = messagesFromJson("[" + json + "]");
        return messages.get(0);
    }

    public static String slotsToJson(Map<String, Object> slots) {
        try {
            return MAPPER.writeValueAsString(slots == null ? Map.of() : slots);
        } catch (Exception e) {
            throw new IllegalStateException("槽位序列化失败: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> slotsFromJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(MAPPER.readValue(json, MAP_TYPE));
        } catch (Exception e) {
            throw new IllegalStateException("槽位反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 工具调用参数是 Map，序列化回去时保持原样即可，这里只做类型说明用途。
     */
    public static List<ToolCall> toolCallsFromJson(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<List<ToolCall>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("工具调用反序列化失败: " + e.getMessage(), e);
        }
    }
}
