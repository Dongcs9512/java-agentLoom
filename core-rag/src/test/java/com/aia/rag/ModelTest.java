package com.aia.rag;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelTest {

    @Test
    void vectorRecordHoldsFields() {
        VectorRecord r = new VectorRecord("id1", "text", new float[]{1.0f, 2.0f}, Map.of("k", "v"));
        assertEquals("id1", r.id());
        assertEquals("text", r.text());
        assertEquals(2, r.vector().length);
        assertEquals("v", r.metadata().get("k"));
    }

    @Test
    void searchHitHoldsFields() {
        SearchHit h = new SearchHit("id1", "text", 0.9f, Map.of());
        assertEquals("id1", h.id());
        assertEquals("text", h.text());
        assertEquals(0.9f, h.score(), 0.001);
    }

    @Test
    void vectorQueryHoldsFields() {
        VectorQuery q = new VectorQuery(new float[]{1f}, 3, Map.of());
        assertEquals(3, q.topK());
        assertEquals(1, q.vector().length);
    }
}
