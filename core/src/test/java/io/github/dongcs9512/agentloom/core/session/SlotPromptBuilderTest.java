package io.github.dongcs9512.agentloom.core.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotPromptBuilderTest {

    private static final List<Slot> SLOTS = List.of(
            new Slot("incident", "具体是什么事？比如被辞退、欠薪", true),
            new Slot("when", "事情发生在什么时候？", true),
            new Slot("location", "工作地在哪个城市？", false));

    @Test
    void allSlotsMissingWhenSessionIsEmpty() {
        assertEquals(3, SlotPromptBuilder.missing(SLOTS, new Session("s1", "a1")).size());
    }

    @Test
    void collectedSlotsAreNoLongerMissing() {
        Session session = new Session("s1", "a1");
        session.putSlot("incident", "被公司辞退");
        session.putSlot("location", "杭州");

        List<Slot> missing = SlotPromptBuilder.missing(SLOTS, session);

        assertEquals(1, missing.size());
        assertEquals("when", missing.get(0).name());
    }

    @Test
    void promptListsCollectedAndMissing() {
        Session session = new Session("s1", "a1");
        session.putSlot("incident", "被公司辞退");

        String prompt = SlotPromptBuilder.build(SLOTS, session);

        assertTrue(prompt.contains("被公司辞退"), prompt);
        assertTrue(prompt.contains("事情发生在什么时候？"), prompt);
        assertFalse(prompt.contains("具体是什么事？比如被辞退、欠薪"), "已收集的槽位不该再追问");
    }

    @Test
    void promptIsEmptyWhenNoSlotsConfigured() {
        assertEquals("", SlotPromptBuilder.build(List.of(), new Session("s1", "a1")));
    }

    @Test
    void statusOfReportsWhatIsStillMissing() {
        Session session = new Session("s1", "a1");
        session.putSlot("incident", "被公司辞退");

        String status = SlotPromptBuilder.statusOf(SLOTS, session);

        assertTrue(status.contains("incident"), status);
        assertTrue(status.contains("when"), status);
    }

    @Test
    void optionalSlotsLeftDoNotBlockConclusion() {
        Session session = new Session("s1", "a1");
        session.putSlot("incident", "被欠薪三个月");
        session.putSlot("when", "今年七月");

        String prompt = SlotPromptBuilder.build(SLOTS, session);

        assertTrue(prompt.contains("可以"), prompt);
        assertTrue(prompt.contains("分析"), prompt);
        assertFalse(prompt.contains("收集齐之前不要下结论"),
                "只剩可选槽位时不应再阻止下结论: " + prompt);
    }

    @Test
    void requiredSlotsLeftStillBlockConclusion() {
        Session session = new Session("s1", "a1");
        session.putSlot("incident", "被欠薪");

        String prompt = SlotPromptBuilder.build(SLOTS, session);

        assertTrue(prompt.contains("收集齐之前不要下结论"), prompt);
    }
}
