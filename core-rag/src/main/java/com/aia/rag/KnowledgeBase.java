package com.aia.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KnowledgeBase {

    private final EmbeddingModel embedder;
    private final VectorStore store;
    private final TextChunker chunker;
    private final int chunkSize;
    private final int overlap;
    private final Map<String, List<String>> docChunks = new LinkedHashMap<>();

    public KnowledgeBase(EmbeddingModel embedder, VectorStore store, TextChunker chunker, int chunkSize, int overlap) {
        this.embedder = embedder;
        this.store = store;
        this.chunker = chunker;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public void addDocument(String docId, String text) {
        List<String> chunks = chunker.chunk(text, chunkSize, overlap);
        List<VectorRecord> records = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = docId + "#" + i;
            records.add(new VectorRecord(chunkId, chunks.get(i), embedder.embed(chunks.get(i)), Map.of("docId", docId)));
            ids.add(chunkId);
        }
        store.upsert(records);
        docChunks.put(docId, ids);
    }

    public List<SearchHit> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return store.search(new VectorQuery(embedder.embed(query), topK, Map.of()));
    }

    public void updateChunk(String chunkId, String newText) {
        if (!containsChunk(chunkId)) {
            return;
        }
        store.update(List.of(new VectorRecord(chunkId, newText, embedder.embed(newText), Map.of())));
    }

    public void deleteDocument(String docId) {
        List<String> ids = docChunks.remove(docId);
        if (ids != null && !ids.isEmpty()) {
            store.delete(ids);
        }
    }

    private boolean containsChunk(String chunkId) {
        for (List<String> ids : docChunks.values()) {
            if (ids.contains(chunkId)) {
                return true;
            }
        }
        return false;
    }
}
