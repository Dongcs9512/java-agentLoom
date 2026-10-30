package com.aia.core.session;

import com.aia.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenEstimatorTest {

    @Test
    void nullAndEmptyAreZero() {
        assertEquals(0, TokenEstimator.estimate((String) null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void asciiCountsRoughlyFourCharsPerToken() {
        assertEquals(2, TokenEstimator.estimate("hello"));
        assertEquals(2, TokenEstimator.estimate("abcdefgh"));
        assertEquals(3, TokenEstimator.estimate("abcdefghi"));
    }

    @Test
    void cjkCountsOneTokenPerChar() {
        assertEquals(2, TokenEstimator.estimate("你好"));
        assertEquals(6, TokenEstimator.estimate("劳动纠纷咨询"));
    }

    @Test
    void mixedTextAddsBothParts() {
        assertEquals(4, TokenEstimator.estimate("你好hello"));
    }

    @Test
    void messagesAddPerMessageOverhead() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("你是客服"),
                ChatMessage.user("你好"));

        // 4+2 内容 token + 每条 4 的开销
        assertEquals(14, TokenEstimator.estimate(messages));
    }

    @Test
    void toolCallsAreCountedToo() {
        ChatMessage withTool = ChatMessage.assistantWithToolCalls(List.of());

        assertEquals(4, TokenEstimator.estimate(List.of(withTool)));
    }
}
