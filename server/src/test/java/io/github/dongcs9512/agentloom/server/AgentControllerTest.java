package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.llm.ChatMessage;
import io.github.dongcs9512.agentloom.llm.ChatModel;
import io.github.dongcs9512.agentloom.llm.ChatModelCapability;
import io.github.dongcs9512.agentloom.llm.ChatRequest;
import io.github.dongcs9512.agentloom.llm.ChatResponse;
import io.github.dongcs9512.agentloom.llm.Usage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AgentControllerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public ChatModelFactory testChatModelFactory() {
            return name -> new ChatModel() {
                @Override
                public ChatResponse chat(ChatRequest request) {
                    return new ChatResponse(ChatMessage.assistant("hi"), "stop", new Usage(1, 1));
                }

                @Override
                public Set<ChatModelCapability> capabilities() {
                    return Set.of(ChatModelCapability.TOOL_CALLING);
                }
            };
        }
    }

    @Autowired
    MockMvc mockMvc;

    private static final String AGENT_JSON =
            "{\"id\":\"a1\",\"systemPrompt\":\"sys\",\"model\":\"m\",\"toolNames\":[],\"skillNames\":[],\"temperature\":null,\"maxTokens\":null}";

    @Test
    void listAgentsReturns200() throws Exception {
        mockMvc.perform(get("/api/agents")).andExpect(status().isOk());
    }

    @Test
    void saveAndListAgentRoundTrip() throws Exception {
        mockMvc.perform(post("/api/agents").contentType("application/json").content(AGENT_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("a1"));
    }

    @Test
    void chatReturnsAnswer() throws Exception {
        mockMvc.perform(post("/api/agents").contentType("application/json").content(AGENT_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/chat/a1").contentType("application/json")
                        .content("{\"message\":\"我想咨询劳动纠纷的处理流程\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("hi"));
    }

    @Test
    void greetingIsAnsweredWithCannedReplyWithoutCallingModel() throws Exception {
        mockMvc.perform(post("/api/agents").contentType("application/json").content(AGENT_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/chat/a1").contentType("application/json").content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("法律咨询助手")));
    }
}
