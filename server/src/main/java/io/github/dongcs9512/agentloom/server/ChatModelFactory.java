package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.llm.ChatModel;

@FunctionalInterface
public interface ChatModelFactory {
    ChatModel create(String modelName);
}
