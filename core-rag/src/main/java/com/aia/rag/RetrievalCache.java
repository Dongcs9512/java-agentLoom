package com.aia.rag;

import java.util.List;
import java.util.Optional;

/**
 * 检索结果缓存：缓存「这个问题命中了哪几篇片段」，而不是最终回答。
 *
 * <p>和答案缓存相比安全得多——模型仍会针对每个用户的案情重新作答，
 * 省掉的是向量化 + 向量检索 + 重排这三次外部调用。
 */
public interface RetrievalCache {

    Optional<List<SearchHit>> get(String scope, String query);

    void put(String scope, String query, List<SearchHit> hits);

    /** 知识库有写入时必须清空，否则新增/修改的文档在 TTL 内搜不到。 */
    void clear();
}
