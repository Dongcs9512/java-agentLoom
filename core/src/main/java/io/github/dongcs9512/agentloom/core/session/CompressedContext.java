package io.github.dongcs9512.agentloom.core.session;

import io.github.dongcs9512.agentloom.llm.ChatMessage;

import java.util.List;

/**
 * @param summary  压缩后的历史摘要，可为 null
 * @param messages 保留下来逐字发送的消息
 */
public record CompressedContext(String summary, List<ChatMessage> messages) {}
