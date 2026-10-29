package com.aia.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class InMemoryVectorStore implements VectorStore {

    private final int dimension;
    private final Map<String, VectorRecord> records = new LinkedHashMap<>();

    public InMemoryVectorStore(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("向量维度必须大于 0");
        }
        this.dimension = dimension;
    }

    @Override
    public void upsert(List<VectorRecord> records) {
        for (VectorRecord record : records) {
            checkDimension(record.vector());
            this.records.put(record.id(), record);
        }
    }

    @Override
    public List<SearchHit> search(VectorQuery query) {
        checkDimension(query.vector());
        List<SearchHit> hits = new ArrayList<>();
        for (VectorRecord record : records.values()) {
            hits.add(new SearchHit(record.id(), record.text(), cosine(query.vector(), record.vector()), record.metadata()));
        }
        hits.sort((a, b) -> Float.compare(b.score(), a.score()));
        return hits.subList(0, Math.min(query.topK(), hits.size()));
    }

    @Override
    public void delete(List<String> ids) {
        for (String id : ids) {
            records.remove(id);
        }
    }

    @Override
    public void update(List<VectorRecord> records) {
        upsert(records);
    }

    @Override
    public Set<VectorStoreCapability> capabilities() {
        return Set.of(VectorStoreCapability.BATCH_UPSERT);
    }

    private void checkDimension(float[] vector) {
        if (vector == null || vector.length != dimension) {
            throw new IllegalArgumentException("向量维度不匹配: 期望 " + dimension
                    + (vector == null ? "" : " 实际 " + vector.length));
        }
    }

    private static float cosine(float[] a, float[] b) {
        float dot = 0f;
        float normA = 0f;
        float normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0f || normB == 0f) {
            return 0f;
        }
        return dot / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
