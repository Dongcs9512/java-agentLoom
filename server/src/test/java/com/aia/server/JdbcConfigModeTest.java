package com.aia.server;

import com.aia.config.JdbcConfigRepository;
import com.aia.core.AgentConfig;
import com.aia.core.ConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "aia.config.mode=jdbc",
        "aia.config.datasource.url=jdbc:h2:mem:cfgtest;DB_CLOSE_DELAY=-1",
        "aia.config.datasource.username=sa",
        "aia.config.datasource.password=",
        "aia.config.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
class JdbcConfigModeTest {

    @Autowired
    ConfigRepository configRepository;

    @Autowired
    MockMvc mockMvc;

    @Test
    void repositoryBeanIsJdbcBacked() {
        assertInstanceOf(JdbcConfigRepository.class, configRepository);
    }

    @Test
    void agentSavedViaHttpLandsInDatabase() throws Exception {
        String body = "{\"id\":\"jdbc-a1\",\"systemPrompt\":\"sys\",\"model\":\"m\","
                + "\"toolNames\":[\"*\"],\"skillNames\":[],\"temperature\":null,\"maxTokens\":null}";

        mockMvc.perform(post("/api/agents")
                        .contentType(new MediaType("application", "json", StandardCharsets.UTF_8))
                        .content(body))
                .andExpect(status().isOk());

        assertTrue(configRepository.loadAgents().stream()
                .map(AgentConfig::id)
                .anyMatch("jdbc-a1"::equals), "agent 应已写入数据库");
    }
}
