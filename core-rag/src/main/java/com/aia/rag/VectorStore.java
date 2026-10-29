package com.aia.rag;

import java.util.List;
import java.util.Set;

public interface VectorStore {
    void upsert(List<VectorRecord> records);

    List<SearchHit> search(VectorQuery query);

    void delete(List<String> ids);

    void update(List<VectorRecord> records);

    Set<VectorStoreCapability> capabilities();
}
