package io.github.dongcs9512.agentloom.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatBaseUrlResolutionTest {

    @Test
    void explicitBaseUrlWins() {
        assertEquals("https://my.gateway/v1",
                AgentConfiguration.resolveBaseUrl("deepseek", "https://my.gateway/v1"));
    }

    @Test
    void presetUsedWhenBaseUrlBlank() {
        assertEquals("https://api.deepseek.com/v1", AgentConfiguration.resolveBaseUrl("deepseek", ""));
        assertEquals("https://api.siliconflow.cn/v1", AgentConfiguration.resolveBaseUrl("SiliconFlow", null));
    }

    @Test
    void unknownOrMissingProviderYieldsEmpty() {
        assertEquals("", AgentConfiguration.resolveBaseUrl("", ""));
        assertEquals("", AgentConfiguration.resolveBaseUrl(null, null));
        assertEquals("", AgentConfiguration.resolveBaseUrl("nope", ""));
    }
}
