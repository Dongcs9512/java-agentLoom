package com.aia.core.session;

import com.aia.llm.ChatMessage;
import com.aia.llm.ChatModel;
import com.aia.llm.ChatModelConfig;
import com.aia.llm.ChatRequest;

import java.util.List;
import java.util.Optional;

/**
 * 让模型做分诊：一次轻量调用，把用户描述归到某个专业方向。
 * 最准但最慢（数百毫秒到一两秒），适合作为级联路由的最后一级，或用于模糊场景兜底。
 */
public final class LlmScenarioRouter implements ScenarioRouter {

    private static final String SYSTEM_PROMPT =
            "你是法律咨询的分诊员。根据用户的描述判断属于哪一类法律问题，只输出类别标识，不要解释。"
                    + "类别标识必须原样取自下面给出的列表；如果明显不属于任何一类，只输出 UNKNOWN。";

    public record Category(String agentId, String description) {
    }

    private final ChatModel model;
    private final List<Category> categories;
    private final String defaultAgentId;

    public LlmScenarioRouter(ChatModel model, List<Category> categories, String defaultAgentId) {
        this.model = model;
        this.categories = categories == null ? List.of() : List.copyOf(categories);
        this.defaultAgentId = defaultAgentId == null || defaultAgentId.isBlank() ? null : defaultAgentId;
    }

    @Override
    public Optional<String> route(String text, Session session) {
        StringBuilder content = new StringBuilder(text == null ? "" : text);
        if (session != null) {
            session.slots().forEach((name, value) -> {
                if (!name.startsWith("__")) {
                    content.append(' ').append(value);
                }
            });
        }
        if (content.toString().isBlank()) {
            return Optional.ofNullable(defaultAgentId);
        }

        StringBuilder prompt = new StringBuilder("可选类别：\n");
        for (Category category : categories) {
            prompt.append("- ").append(category.agentId()).append('：').append(category.description()).append('\n');
        }
        prompt.append("\n用户描述：").append(content);

        String reply = model.chat(new ChatRequest(
                List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(prompt.toString())),
                List.of(), new ChatModelConfig(0.0, 20))).message().content();
        if (reply == null) {
            return Optional.ofNullable(defaultAgentId);
        }
        String normalized = reply.trim().toLowerCase(java.util.Locale.ROOT);
        for (Category category : categories) {
            if (normalized.contains(category.agentId().toLowerCase(java.util.Locale.ROOT))) {
                return Optional.of(category.agentId());
            }
        }
        return Optional.ofNullable(defaultAgentId);
    }
}
