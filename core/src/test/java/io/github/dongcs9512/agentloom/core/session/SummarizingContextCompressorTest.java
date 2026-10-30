package io.github.dongcs9512.agentloom.core.session;

import io.github.dongcs9512.agentloom.llm.ChatMessage;
import io.github.dongcs9512.agentloom.llm.ChatModel;
import io.github.dongcs9512.agentloom.llm.ChatModelCapability;
import io.github.dongcs9512.agentloom.llm.ChatRequest;
import io.github.dongcs9512.agentloom.llm.ChatResponse;
import io.github.dongcs9512.agentloom.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarizingContextCompressorTest {

    static class FakeChatModel implements ChatModel {
        private final String reply;
        private final RuntimeException error;
        final List<ChatRequest> requests = new ArrayList<>();

        FakeChatModel(String reply, RuntimeException error) {
            this.reply = reply;
            this.error = error;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            if (error != null) {
                throw error;
            }
            return new ChatResponse(ChatMessage.assistant(reply), "stop", new Usage(1, 1));
        }

        @Override
        public Set<ChatModelCapability> capabilities() {
            return Set.of(ChatModelCapability.TOOL_CALLING);
        }
    }

    private static List<ChatMessage> conversation(int turns) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            messages.add(ChatMessage.user("用户第 " + i + " 句，这里是一段比较长的话用来撑大 token 数"));
            messages.add(ChatMessage.assistant("助手第 " + i + " 句回复"));
        }
        return messages;
    }

    @Test
    void underThresholdLeavesEverythingUntouched() {
        FakeChatModel model = new FakeChatModel("不该被调用", null);
        SummarizingContextCompressor compressor = new SummarizingContextCompressor(model, 10_000, 4);
        List<ChatMessage> messages = conversation(1);

        CompressedContext result = compressor.compress(null, messages);

        assertSame(messages, result.messages());
        assertEquals(null, result.summary());
        assertTrue(model.requests.isEmpty(), "未超阈值不应调用模型");
    }

    @Test
    void overThresholdSummarizesOlderMessagesAndKeepsRecent() {
        FakeChatModel model = new FakeChatModel("用户咨询劳动纠纷，涉及违法辞退", null);
        SummarizingContextCompressor compressor = new SummarizingContextCompressor(model, 50, 4);
        List<ChatMessage> messages = conversation(5);

        CompressedContext result = compressor.compress(null, messages);

        assertEquals("用户咨询劳动纠纷，涉及违法辞退", result.summary());
        assertEquals(4, result.messages().size());
        assertEquals("助手第 4 句回复", result.messages().get(3).content());
    }

    @Test
    void summaryRequestCarriesExistingSummaryAndOlderMessages() {
        FakeChatModel model = new FakeChatModel("合并后的摘要", null);
        SummarizingContextCompressor compressor = new SummarizingContextCompressor(model, 10, 4);
        List<ChatMessage> messages = conversation(4);

        compressor.compress("此前用户提到被欠薪", messages);

        String prompt = model.requests.get(0).messages().stream()
                .map(ChatMessage::content)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(prompt.contains("此前用户提到被欠薪"), prompt);
        assertTrue(prompt.contains("用户第 0 句"), prompt);
    }

    @Test
    void summarizationFailureFallsBackToTruncation() {
        FakeChatModel model = new FakeChatModel(null, new IllegalStateException("模型不可用"));
        SummarizingContextCompressor compressor = new SummarizingContextCompressor(model, 50, 4);
        List<ChatMessage> messages = conversation(5);

        CompressedContext result = compressor.compress("旧摘要", messages);

        assertEquals("旧摘要", result.summary(), "压缩失败应保留原摘要");
        assertEquals(4, result.messages().size(), "压缩失败仍应截断");
    }

    @Test
    void nothingToCompressWhenMessagesFitInRecentWindow() {
        FakeChatModel model = new FakeChatModel("不该被调用", null);
        SummarizingContextCompressor compressor = new SummarizingContextCompressor(model, 1, 10);
        List<ChatMessage> messages = conversation(2);

        CompressedContext result = compressor.compress(null, messages);

        assertSame(messages, result.messages());
        assertTrue(model.requests.isEmpty());
    }
}
