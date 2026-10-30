package io.github.dongcs9512.agentloom.core.session;

import io.github.dongcs9512.agentloom.llm.ChatMessage;

import java.util.List;

/**
 * 粗略的 token 估算，用于触发上下文压缩。不追求精确，只求量级正确：
 * 中日韩字符按 1 token/字，其余字符按 4 字符/token，每条消息再加固定开销。
 */
public final class TokenEstimator {

    private static final int MESSAGE_OVERHEAD = 4;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (isWide(codePoint)) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(codePoint);
        }
        return cjk + (other + 3) / 4;
    }

    public static int estimate(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total += MESSAGE_OVERHEAD + estimate(message.content());
        }
        return total;
    }

    private static boolean isWide(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)      // CJK 统一表意
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)  // 扩展 A
                || (codePoint >= 0x3000 && codePoint <= 0x303F)  // 中日韩标点
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF); // 全角字符
    }
}
