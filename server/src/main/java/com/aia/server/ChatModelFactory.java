package com.aia.server;

import com.aia.llm.ChatModel;

@FunctionalInterface
public interface ChatModelFactory {
    ChatModel create(String modelName);
}
