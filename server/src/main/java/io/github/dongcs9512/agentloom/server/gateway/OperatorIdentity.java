package io.github.dongcs9512.agentloom.server.gateway;

/**
 * 当前请求的操作者身份。
 *
 * @param id   坐席/系统唯一标识（真实系统里通常是工号或用户 id）
 * @param name 展示名，写进会话记录用
 * @param type AI / HUMAN / SYSTEM
 */
public record OperatorIdentity(String id, String name, Type type) {

    public enum Type {
        AI, HUMAN, SYSTEM
    }

    public static OperatorIdentity human(String id, String name) {
        return new OperatorIdentity(id, name == null || name.isBlank() ? id : name, Type.HUMAN);
    }

    /** 写入 Session.operator 的值，格式 human:<name> / ai:<agentId>。 */
    public String sessionValue() {
        return switch (type) {
            case HUMAN -> "human:" + name;
            case AI -> "ai:" + name;
            case SYSTEM -> "system:" + name;
        };
    }
}
