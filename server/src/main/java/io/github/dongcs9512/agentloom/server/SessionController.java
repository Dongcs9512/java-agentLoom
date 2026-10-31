package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.session.Session;
import io.github.dongcs9512.agentloom.server.gateway.GatewaySettings;
import io.github.dongcs9512.agentloom.server.gateway.OperatorIdentity;
import io.github.dongcs9512.agentloom.server.gateway.OperatorResolver;
import io.github.dongcs9512.agentloom.core.session.SessionStore;
import io.github.dongcs9512.agentloom.llm.ChatMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话接口：给人工坐席系统用。
 *
 * <p>AI 转人工、人工转人工，全程都是同一个 sessionId——
 * 新坐席接手时用 GET 拉历史，就能看到 AI 之前聊了什么、收集了哪些信息。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    public record HandoffRequest(String operator, String note) {
    }

    public record MessageRequest(String text, String operator) {
    }

    private final ObjectProvider<SessionSupport> supportProvider;
    private final OperatorResolver operatorResolver;
    private final GatewaySettings gatewaySettings;

    public SessionController(ObjectProvider<SessionSupport> supportProvider, OperatorResolver operatorResolver,
                             GatewaySettings gatewaySettings) {
        this.supportProvider = supportProvider;
        this.operatorResolver = operatorResolver;
        this.gatewaySettings = gatewaySettings;
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String sessionId) {
        Session session = load(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(view(session));
    }

    /** 人工坐席发言：写进同一个会话，AI 接手时能看到（带【人工客服 X】前缀）。 */
    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<Map<String, Object>> appendMessage(@PathVariable String sessionId,
                                                             @RequestHeader Map<String, String> headers,
                                                             @RequestBody MessageRequest request) {
        Session session = load(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        String text = request.text() == null ? "" : request.text().trim();
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
        }
        String operator = operatorResolver.resolve(headers, request.operator())
                .map(OperatorIdentity::name)
                .orElseGet(() -> currentOperatorName(session));
        if (operator.isBlank()) {
            return rejectUnauthenticated();
        }
        session.addMessage(ChatMessage.assistant("【人工客服 " + operator + "】" + text));
        store().save(session);
        return ResponseEntity.ok(view(session));
    }

    /** 转人工：会话切到人工接待，AI 从此不再回复，只记录用户消息。 */
    @PostMapping("/{sessionId}/handoff")
    public ResponseEntity<Map<String, Object>> handoff(@PathVariable String sessionId,
                                                       @RequestHeader Map<String, String> headers,
                                                       @RequestBody HandoffRequest request) {
        Session session = load(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        java.util.Optional<OperatorIdentity> identity = operatorResolver.resolve(headers, request.operator());
        if (identity.isEmpty()) {
            return rejectUnauthenticated();
        }
        OperatorIdentity operator = identity.get();
        session.setOperator(operator.sessionValue());
        StringBuilder note = new StringBuilder("【系统】已转人工，当前由 " + operator.name() + " 接待");
        if (request.note() != null && !request.note().isBlank()) {
            note.append("（").append(request.note().trim()).append("）");
        }
        session.addMessage(ChatMessage.system(note.toString()));
        store().save(session);
        return ResponseEntity.ok(view(session));
    }

    /** 人工处理完，交回 AI：后续消息重新由 AI 回答，历史不丢。 */
    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String sessionId) {
        Session session = load(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        String previous = currentOperatorName(session);
        session.setOperator(null);
        session.addMessage(ChatMessage.system("【系统】已结束人工接待"
                + (previous.isEmpty() ? "" : "（原坐席 " + previous + "）") + "，本会话交回 AI"));
        store().save(session);
        return ResponseEntity.ok(view(session));
    }

    /** 解析不出身份时：开启强制鉴权返回 401，否则按参数缺失返回 400。 */
    private ResponseEntity<Map<String, Object>> rejectUnauthenticated() {
        if (gatewaySettings.requireOperator()) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "未识别到操作者身份", "hint", "请在网关注入操作者信息或关闭 agentloom.gateway.require-operator"));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "operator 不能为空"));
    }

    private Session load(String sessionId) {
        return store().load(sessionId).orElse(null);
    }

    private SessionStore store() {
        SessionSupport support = supportProvider.getIfAvailable();
        if (support == null || !support.hasSession()) {
            throw new IllegalStateException("未启用会话能力（agentloom.session.enabled=false）");
        }
        return support.store();
    }

    private static String currentOperatorName(Session session) {
        String operator = session.operator();
        if (operator == null || operator.isBlank()) {
            return "";
        }
        return operator.startsWith("human:") ? operator.substring("human:".length()) : operator;
    }

    private static Map<String, Object> view(Session session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", session.id());
        body.put("agentId", session.agentId());
        body.put("operator", session.operator());
        body.put("handledByHuman", session.handledByHuman());
        body.put("summary", session.summary());
        // __ 开头的是工作流/分派内部状态，不暴露给坐席系统
        Map<String, Object> visibleSlots = new LinkedHashMap<>();
        session.slots().forEach((key, value) -> {
            if (!key.startsWith("__")) {
                visibleSlots.put(key, value);
            }
        });
        body.put("slots", visibleSlots);
        // 当前由哪个专业 agent 负责：内置分派写 __scenario，工作流写 __wf_routed
        String currentAgent = session.slot(io.github.dongcs9512.agentloom.core.ScenarioDispatchTool.SCENARIO_SLOT);
        if (currentAgent.isBlank()) {
            currentAgent = session.slot(
                    io.github.dongcs9512.agentloom.core.workflow.WorkflowEngine.SLOT_ROUTED);
        }
        body.put("currentAgent", currentAgent);
        body.put("messages", session.messages());
        body.put("updatedAt", session.updatedAt());
        return body;
    }
}
