package com.aia.core.session;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerCacheTest {

    @Test
    void normalizeIgnoresPunctuationWhitespaceAndCase() {
        assertEquals("你好我要退款", QuestionNormalizer.normalize(" 你好，我要退款！ "));
        assertEquals("hello退款", QuestionNormalizer.normalize("Hello 退款?"));
        assertEquals("", QuestionNormalizer.normalize(null));
    }

    @Test
    void putThenGetHits() {
        AnswerCache cache = new InMemoryAnswerCache(10);
        cache.put("a1", "退货怎么退", "签收后 7 天内可退");

        assertEquals(Optional.of("签收后 7 天内可退"), cache.get("a1", "退货怎么退？"));
    }

    @Test
    void differentAgentDoesNotHit() {
        AnswerCache cache = new InMemoryAnswerCache(10);
        cache.put("a1", "退货怎么退", "答案");

        assertTrue(cache.get("a2", "退货怎么退").isEmpty());
    }

    @Test
    void differentQuestionDoesNotHit() {
        AnswerCache cache = new InMemoryAnswerCache(10);
        cache.put("a1", "退货怎么退", "答案");

        assertTrue(cache.get("a1", "发货要多久").isEmpty());
    }

    @Test
    void evictsWhenExceedingCapacity() {
        AnswerCache cache = new InMemoryAnswerCache(2);
        cache.put("a1", "q1", "a1");
        cache.put("a1", "q2", "a2");
        cache.put("a1", "q3", "a3");

        assertTrue(cache.get("a1", "q1").isEmpty(), "最旧的应被淘汰");
        assertTrue(cache.get("a1", "q3").isPresent());
    }

    @Test
    void compositeWritesBothAndFallsBackToArchive() {
        InMemoryAnswerCache hot = new InMemoryAnswerCache(10);
        InMemoryAnswerCache archive = new InMemoryAnswerCache(10);
        AnswerCache composite = new CompositeAnswerCache(hot, archive, Runnable::run);

        composite.put("a1", "退货", "7 天");

        assertTrue(hot.get("a1", "退货").isPresent());
        assertTrue(archive.get("a1", "退货").isPresent());

        InMemoryAnswerCache coldHot = new InMemoryAnswerCache(10);
        InMemoryAnswerCache coldArchive = new InMemoryAnswerCache(10);
        coldArchive.put("a1", "退货", "7 天");
        AnswerCache coldComposite = new CompositeAnswerCache(coldHot, coldArchive, Runnable::run);

        assertEquals(Optional.of("7 天"), coldComposite.get("a1", "退货"));
        assertTrue(coldHot.get("a1", "退货").isPresent(), "回源后应回填热缓存");
    }
}
