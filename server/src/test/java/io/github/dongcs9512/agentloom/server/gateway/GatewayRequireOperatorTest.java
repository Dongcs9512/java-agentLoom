package io.github.dongcs9512.agentloom.server.gateway;

import io.github.dongcs9512.agentloom.core.session.Session;
import io.github.dongcs9512.agentloom.core.session.SessionStore;
import io.github.dongcs9512.agentloom.server.SessionSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 开启强制鉴权后，没有身份的请求应被拒绝；有网关注入的身份时以 header 为准。
 */
@SpringBootTest(properties = {
        "agentloom.gateway.require-operator=true",
        "agentloom.gateway.trust-operator-headers=true"
})
@AutoConfigureMockMvc
class GatewayRequireOperatorTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectProvider<SessionSupport> supportProvider;

    private static MediaType json() {
        return new MediaType("application", "json", StandardCharsets.UTF_8);
    }

    @BeforeEach
    void seed() {
        SessionStore store = supportProvider.getObject().store();
        store.save(new Session("gw-1", "legal-intake"));
    }

    @Test
    void handoffWithoutIdentityIsRejected() throws Exception {
        mockMvc.perform(post("/api/sessions/gw-1/handoff").contentType(json()).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void handoffWithGatewayHeaderSucceeds() throws Exception {
        mockMvc.perform(post("/api/sessions/gw-1/handoff").contentType(json())
                        .header("X-Operator-Id", "u1001")
                        .header("X-Operator-Name", "张三")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operator").value("human:张三"))
                .andExpect(jsonPath("$.handledByHuman").value(true));
    }

    @Test
    void bodyOperatorCannotSpoofIdentityWhenHeadersTrusted() throws Exception {
        // 开了信任 header 但没有 header 时，仍允许用 body（联调用）；有 header 时以 header 为准
        mockMvc.perform(post("/api/sessions/gw-1/handoff").contentType(json())
                        .header("X-Operator-Id", "u1001")
                        .header("X-Operator-Name", "张三")
                        .content("{\"operator\":\"冒名者\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operator").value("human:张三"));
    }
}
