package io.github.dongcs9512.agentloom.rag.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerNormalizerTest {

    @Test
    void chineseNumeralsBecomeDigits() {
        assertEquals("11个月", AnswerNormalizer.normalize("十一个月"));
        assertEquals("200", AnswerNormalizer.normalize("二百"));
        assertEquals("30", AnswerNormalizer.normalize("三十"));
        assertEquals("105", AnswerNormalizer.normalize("一百零五"));
        assertEquals("23", AnswerNormalizer.normalize("二十三"));
    }

    @Test
    void percentagesLoseTheirDecoration() {
        assertEquals("80", AnswerNormalizer.normalize("百分之八十"));
        assertEquals("200", AnswerNormalizer.normalize("200%"));
        assertEquals("200", AnswerNormalizer.normalize("百分之二百"));
    }

    @Test
    void unitsAndSpacingAreUnified() {
        assertEquals("11个月", AnswerNormalizer.normalize("11 个月"));
        assertEquals("30天", AnswerNormalizer.normalize("30日"));
        assertEquals("30天", AnswerNormalizer.normalize("三十日"));
    }

    @Test
    void plainChineseTextSurvives() {
        assertEquals("赔偿金", AnswerNormalizer.normalize("赔偿金"));
        assertEquals("", AnswerNormalizer.normalize(null));
    }

    @Test
    void keywordAndAnswerMeetInTheMiddle() {
        assertTrue(AnswerNormalizer.normalize("支付不低于工资的百分之二百")
                .contains(AnswerNormalizer.normalize("百分之二百")));
        assertTrue(AnswerNormalizer.normalize("休息日加班要付 200% 工资")
                .contains(AnswerNormalizer.normalize("百分之二百")));
        assertTrue(AnswerNormalizer.normalize("最多 11 个月")
                .contains(AnswerNormalizer.normalize("十一个月")));
    }
}
