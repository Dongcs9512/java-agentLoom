package io.github.dongcs9512.agentloom.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryVectorStoreTest {

    @Test
    void searchReturnsTopKOrderedByCosine() {
        InMemoryVectorStore store = new InMemoryVectorStore(2);
        store.upsert(List.of(
                new VectorRecord("a", "aa", new float[]{1, 0}, Map.of()),
                new VectorRecord("b", "bb", new float[]{0.9f, 0.1f}, Map.of()),
                new VectorRecord("c", "cc", new float[]{0, 1}, Map.of())));

        List<SearchHit> hits = store.search(new VectorQuery(new float[]{1, 0}, 2, Map.of()));

        assertEquals(2, hits.size());
        assertEquals("a", hits.get(0).id());
        assertEquals("b", hits.get(1).id());
        assertTrue(hits.get(0).score() >= hits.get(1).score());
    }

    @Test
    void upsertRejectsWrongDimension() {
        InMemoryVectorStore store = new InMemoryVectorStore(3);
        assertThrows(IllegalArgumentException.class,
                () -> store.upsert(List.of(new VectorRecord("a", "a", new float[]{1, 0}, Map.of()))));
    }

    @Test
    void deleteRemoves() {
        InMemoryVectorStore store = new InMemoryVectorStore(2);
        store.upsert(List.of(new VectorRecord("a", "a", new float[]{1, 0}, Map.of())));
        store.delete(List.of("a"));
        assertTrue(store.search(new VectorQuery(new float[]{1, 0}, 10, Map.of())).isEmpty());
    }

    @Test
    void updateReplaces() {
        InMemoryVectorStore store = new InMemoryVectorStore(2);
        store.upsert(List.of(new VectorRecord("a", "old", new float[]{1, 0}, Map.of())));
        store.update(List.of(new VectorRecord("a", "new", new float[]{0, 1}, Map.of())));

        List<SearchHit> hits = store.search(new VectorQuery(new float[]{0, 1}, 1, Map.of()));
        assertEquals("a", hits.get(0).id());
        assertEquals("new", hits.get(0).text());
    }

    @Test
    void topKExceedingSizeReturnsAll() {
        InMemoryVectorStore store = new InMemoryVectorStore(2);
        store.upsert(List.of(new VectorRecord("a", "a", new float[]{1, 0}, Map.of())));
        List<SearchHit> hits = store.search(new VectorQuery(new float[]{1, 0}, 10, Map.of()));
        assertEquals(1, hits.size());
    }
}
