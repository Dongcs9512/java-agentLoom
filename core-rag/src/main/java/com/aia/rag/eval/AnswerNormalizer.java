package com.aia.rag.eval;

import java.util.Map;

/**
 * 评测用的文本归一化：把「百分之二百」「200%」「200」, 「十一个月」「11 个月」,
 * 「30日」「30天」这类等价写法抹平，避免评测因为格式差异误判为答错。
 * 关键词与回答走同一套归一化，语义等价即视为命中。
 */
public final class AnswerNormalizer {

    private static final Map<Character, Integer> DIGITS = Map.ofEntries(
            Map.entry('〇', 0), Map.entry('零', 0), Map.entry('一', 1), Map.entry('二', 2),
            Map.entry('两', 2), Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5),
            Map.entry('六', 6), Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9));

    private static final Map<Character, Integer> UNITS = Map.of('十', 10, '百', 100, '千', 1000);

    private AnswerNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = toHalfWidth(text);
        // 先去掉「百分之」再转数字，否则「百」会被当成单位单独换算
        s = s.replace("百分之", "").replace("%", "");
        s = convertChineseNumbers(s);
        s = s.replace("日", "天");
        return s.replaceAll("[\\s，。！？、；：,.!?;:]", "");
    }

    private static String toHalfWidth(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String convertChineseNumbers(String text) {
        StringBuilder out = new StringBuilder(text.length());
        StringBuilder run = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (DIGITS.containsKey(c) || UNITS.containsKey(c)) {
                run.append(c);
            } else {
                flush(run, out);
                out.append(c);
            }
        }
        flush(run, out);
        return out.toString();
    }

    private static void flush(StringBuilder run, StringBuilder out) {
        if (run.isEmpty()) {
            return;
        }
        out.append(parseRun(run.toString()));
        run.setLength(0);
    }

    private static int parseRun(String run) {
        int section = 0;
        int number = 0;
        for (char c : run.toCharArray()) {
            Integer digit = DIGITS.get(c);
            if (digit != null) {
                number = digit;
                continue;
            }
            int unit = UNITS.get(c);
            section += (number == 0 ? 1 : number) * unit;
            number = 0;
        }
        return section + number;
    }
}
