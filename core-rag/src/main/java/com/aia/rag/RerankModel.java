package com.aia.rag;

/**
 * 重排模型：给定查询和候选文档，按相关性重新打分排序。
 * RAG 里通常先粗召回较多片段，再用重排模型收敛到 topN。
 */
public interface RerankModel {

    /**
     * @param query     查询语句
     * @param documents 候选文档原文
     * @param topN      保留的数量
     * @return 按相关性从高到低排序的结果，{@code index} 指向传入 documents 的下标
     */
    java.util.List<RerankResult> rerank(String query, java.util.List<String> documents, int topN);
}
