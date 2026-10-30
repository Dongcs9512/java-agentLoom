package com.aia.core.session;

import java.util.Locale;

/**
 * 问题归一化：去掉空白与标点、统一大小写，让「退货怎么退？」和「退货怎么退」命中同一条缓存。
 */
public final class QuestionNormalizer {

    /** ASCII 标点 + 常用中文标点（含全角引号，用转义写避免源码里出现引号字符） */
    private static final String PUNCTUATION =
            "[\\s\\p{Punct}　-〿＀-／：-＠［-｀｛-･‘’“”…·—～]";

    private QuestionNormalizer() {
    }

    public static String normalize(String question) {
        if (question == null) {
            return "";
        }
        return question.toLowerCase(Locale.ROOT).replaceAll(PUNCTUATION, "");
    }
}
