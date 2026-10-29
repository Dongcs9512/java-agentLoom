package com.aia.core;

import java.util.List;

public record AgentConfig(
        String id,
        String systemPrompt,
        String model,
        List<String> toolNames,
        List<String> skillNames,
        Double temperature,
        Integer maxTokens) {
}
