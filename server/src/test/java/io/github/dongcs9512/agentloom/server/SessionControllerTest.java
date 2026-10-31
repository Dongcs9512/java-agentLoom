package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.session.Session;
import io.github.dongcs9512.agentloom.core.session.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SessionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    org.springframework.beans.factory.ObjectProvider<SessionSupport> supportProvider;

    private static MediaType json() {
        return new MediaType("application", "json", StandardCharsets.UTF_8);
    }

    private SessionStore store() {
        return supportProvider.getObject().store();
    }

    @BeforeEach
    void seed() {
        Session session = new Session("handoff-1", "legal-intake");
        session.addMessage(io.github.dongcs9512.agentloom.llm.ChatMessage.user("公司把我辞退了"));
        session.addMessage(io.github.dongcs9512.agentloom.llm.ChatMessage.assistant("请问具体是什么事？"));
        session.putSlot("incident", "被辞退");
        session.putSlot("__wf_routed", "legal-labor");
        store().save(session);
    }

    @Test
    void getReturnsHistoryAndSlots() throws Exception {
        mockMvc.perform(get("/api/sessions/handoff-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value("legal-intake"))
                .andExpect(jsonPath("$.slots.incident").value("被辞退"))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.handledByHuman").value(false))
                .andExpect(jsonPath("$.slots.incident").value("被辞退"))
                .andExpect(jsonPath("$.slots.__wf_routed").doesNotExist())
                .andExpect(jsonPath("$.currentAgent").value("legal-labor"));
    }

    @Test
    void missingSessionReturns404() throws Exception {
        mockMvc.perform(get("/api/sessions/nope")).andExpect(status().isNotFound());
    }

    @Test
    void handoffSwitchesOperatorAndKeepsHistory() throws Exception {
        mockMvc.perform(post("/api/sessions/handoff-1/handoff").contentType(json())
                        .content("{\"operator\":\"张三\",\"note\":\"用户要求人工\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operator").value("human:张三"))
                .andExpect(jsonPath("$.handledByHuman").value(true))
                .andExpect(jsonPath("$.messages.length()").value(3));

        Session session = store().load("handoff-1").orElseThrow();
        assertTrue(session.messages().get(2).content().contains("已转人工"), "应写入转接事件");
        assertTrue(session.messages().get(0).content().contains("公司把我辞退了"), "历史不能丢");
    }

    @Test
    void humanMessageIsStoredWithOperatorPrefix() throws Exception {
        mockMvc.perform(post("/api/sessions/handoff-1/handoff").contentType(json())
                .content("{\"operator\":\"张三\"}")).andExpect(status().isOk());

        mockMvc.perform(post("/api/sessions/handoff-1/messages").contentType(json())
                        .content("{\"text\":\"我帮您登记一下\"}"))
                .andExpect(status().isOk());

        Session session = store().load("handoff-1").orElseThrow();
        String last = session.messages().get(session.messages().size() - 1).content();
        assertTrue(last.contains("【人工客服 张三】我帮您登记一下"), last);
    }

    @Test
    void resumeHandsBackToAi() throws Exception {
        mockMvc.perform(post("/api/sessions/handoff-1/handoff").contentType(json())
                .content("{\"operator\":\"张三\"}")).andExpect(status().isOk());

        mockMvc.perform(post("/api/sessions/handoff-1/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handledByHuman").value(false))
                .andExpect(jsonPath("$.slots.incident").value("被辞退"))
                .andExpect(jsonPath("$.slots.__wf_routed").doesNotExist())
                .andExpect(jsonPath("$.currentAgent").value("legal-labor"));
    }

    @Test
    void handoffWithoutOperatorIsRejected() throws Exception {
        mockMvc.perform(post("/api/sessions/handoff-1/handoff").contentType(json()).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
