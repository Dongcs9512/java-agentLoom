package io.github.dongcs9512.agentloom.core;

import io.github.dongcs9512.agentloom.core.session.Slot;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentConfig(
        String id,
        String systemPrompt,
        String model,
        List<String> toolNames,
        List<String> skillNames,
        Double temperature,
        Integer maxTokens,
        List<Slot> slots) {

    /** 兼容不带槽位的旧配置。 */
    public AgentConfig(String id, String systemPrompt, String model, List<String> toolNames,
                       List<String> skillNames, Double temperature, Integer maxTokens) {
        this(id, systemPrompt, model, toolNames, skillNames, temperature, maxTokens, List.of());
    }

    public AgentConfig {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }
}
