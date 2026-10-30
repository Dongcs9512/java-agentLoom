package io.github.dongcs9512.agentloom.core.workflow;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionTest {

    @Test
    void numericGreaterThan() {
        Condition c = Condition.parse("slots.amount > 10000");

        assertTrue(c.evaluate(Map.of("amount", "15000")));
        assertFalse(c.evaluate(Map.of("amount", "5000")));
        assertFalse(c.evaluate(Map.of("amount", "10000")), "等于不算大于");
    }

    @Test
    void numericOperators() {
        assertTrue(Condition.parse("slots.n >= 5").evaluate(Map.of("n", 5)));
        assertTrue(Condition.parse("slots.n <= 5").evaluate(Map.of("n", 5)));
        assertTrue(Condition.parse("slots.n < 6").evaluate(Map.of("n", 5)));
    }

    @Test
    void equalityWithAndWithoutQuotes() {
        assertTrue(Condition.parse("slots.city == \"杭州\"").evaluate(Map.of("city", "杭州")));
        assertTrue(Condition.parse("slots.city == '杭州'").evaluate(Map.of("city", "杭州")));
        assertTrue(Condition.parse("slots.city == 杭州").evaluate(Map.of("city", "杭州")));
        assertFalse(Condition.parse("slots.city == 上海").evaluate(Map.of("city", "杭州")));
    }

    @Test
    void notEqual() {
        assertTrue(Condition.parse("slots.city != 上海").evaluate(Map.of("city", "杭州")));
    }

    @Test
    void contains() {
        assertTrue(Condition.parse("slots.incident contains 辞退").evaluate(Map.of("incident", "被公司辞退")));
        assertFalse(Condition.parse("slots.incident contains 欠薪").evaluate(Map.of("incident", "被公司辞退")));
    }

    @Test
    void missingSlotIsEmptyString() {
        assertTrue(Condition.parse("slots.missing == \"\"").evaluate(Map.of()));
        assertFalse(Condition.parse("slots.missing > 0").evaluate(Map.of("missing", "0")));
    }

    @Test
    void malformedExpressionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Condition.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("amount > 100"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("slots.amount"));
    }

    @Test
    void comparingNonNumbersThrows() {
        Condition c = Condition.parse("slots.amount > 100");

        assertThrows(IllegalStateException.class, () -> c.evaluate(Map.of("amount", "很多")));
    }

    @Test
    void accessorsExposeParsedParts() {
        Condition c = Condition.parse("slots.amount >= 100");

        assertTrue(c.slotName().equals("amount"));
        assertTrue(c.operator() == Condition.Operator.GE);
        assertTrue(c.expected().equals("100"));
    }
}
