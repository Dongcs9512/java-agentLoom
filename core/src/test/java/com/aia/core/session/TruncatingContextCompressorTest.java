package com.aia.core.session;

import com.aia.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TruncatingContextCompressorTest {

    private static List<ChatMessage> conversation(int pairs) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            messages.add(ChatMessage.user("用户第 " + i + " 句，这里是一段比较长的话用来撑大 token 数"));
            messages.add(ChatMessage.assistant("助手第 " + i + " 句回复"));
        }
        return messages;
    }

    @Test
    void underThresholdKeepsEverything() {
        List<ChatMessage> messages = conversation(1);

        CompressedContext result = new TruncatingContextCompressor(1000, 4).compress(null, messages);

        assertSame(messages, result.messages());
    }

    @Test
    void overThresholdKeepsOnlyRecentMessages() {
        List<ChatMessage> messages = conversation(5);

        CompressedContext result = new TruncatingContextCompressor(50, 4).compress("旧摘要", messages);

        assertEquals(4, result.messages().size());
        assertEquals("助手第 4 句回复", result.messages().get(3).content());
        assertEquals("旧摘要", result.summary());
    }
}
