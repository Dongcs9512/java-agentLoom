package com.aia.core.session;

import com.aia.llm.ChatMessage;
import com.aia.llm.ChatModel;
import com.aia.llm.ChatModelCapability;
import com.aia.llm.ChatRequest;
import com.aia.llm.ChatResponse;
import com.aia.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSelectiveContextCompressorTest {

    static class ReplyModel implements ChatModel {
        private final String reply;
        final List<ChatRequest> requests = new ArrayList<>();

        ReplyModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            return new ChatResponse(ChatMessage.assistant(reply), "stop", new Usage(1, 1));
        }

        @Override
        public Set<ChatModelCapability> capabilities() {
            return Set.of(ChatModelCapability.TOOL_CALLING);
        }
    }

    private static List<ChatMessage> conversation(int pairs) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            messages.add(ChatMessage.user("用户第 " + i + " 句，这里是一段比较长的话用来撑大 token 数"));
            messages.add(ChatMessage.assistant("助手第 " + i + " 句回复"));
        }
        return messages;
    }

    @Test
    void keepsModelSelectedMessagesPlusRecentOnes() {
        ReplyModel model = new ReplyModel("{\"summary\":\"用户咨询被辞退\",\"keep\":[0,2]}");
        List<ChatMessage> messages = conversation(5);

        CompressedContext result = new ModelSelectiveContextCompressor(model, 50, 2).compress(null, messages);

        assertEquals("用户咨询被辞退", result.summary());
        // 保留 0、2 两条被模型选中的，加上最近 2 条
        assertEquals(4, result.messages().size());
        assertEquals("用户第 0 句，这里是一段比较长的话用来撑大 token 数", result.messages().get(0).content());
        assertEquals("用户第 1 句，这里是一段比较长的话用来撑大 token 数", result.messages().get(1).content());
        assertEquals("助手第 4 句回复", result.messages().get(3).content());
    }

    @Test
    void outOfRangeIndicesAreIgnored() {
        ReplyModel model = new ReplyModel("{\"summary\":\"摘要\",\"keep\":[0,99]}");

        CompressedContext result = new ModelSelectiveContextCompressor(model, 50, 2)
                .compress(null, conversation(4));

        assertEquals("摘要", result.summary());
        assertEquals(3, result.messages().size());
    }

    @Test
    void unparsableReplyFallsBackToTruncation() {
        ReplyModel model = new ReplyModel("我不知道该怎么总结");

        CompressedContext result = new ModelSelectiveContextCompressor(model, 50, 2)
                .compress("旧摘要", conversation(5));

        assertEquals("旧摘要", result.summary());
        assertEquals(2, result.messages().size(), "解析失败应退化为纯截断");
    }

    @Test
    void underThresholdDoesNotCallModel() {
        ReplyModel model = new ReplyModel("{}");
        List<ChatMessage> messages = conversation(1);

        CompressedContext result = new ModelSelectiveContextCompressor(model, 10_000, 2).compress(null, messages);

        assertEquals(messages, result.messages());
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void promptAsksForStrictJson() {
        ReplyModel model = new ReplyModel("{\"summary\":\"s\",\"keep\":[]}");

        new ModelSelectiveContextCompressor(model, 10, 1).compress(null, conversation(4));

        String prompt = model.requests.get(0).messages().stream()
                .map(ChatMessage::content).reduce("", (a, b) -> a + b);
        assertTrue(prompt.contains("JSON"), prompt);
        assertTrue(prompt.contains("keep"), prompt);
    }
}
