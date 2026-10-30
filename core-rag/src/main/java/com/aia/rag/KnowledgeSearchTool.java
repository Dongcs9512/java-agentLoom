package com.aia.rag;

import com.aia.core.AbstractTool;
import com.aia.core.ToolInvocation;
import com.aia.core.ToolResult;
import com.aia.core.session.QuestionNormalizer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把知识库检索暴露成 agent 可调用的工具（OpenAI function calling）。
 * 检索结果以「序号 + 来源 chunkId + 原文」的形式返回给模型。
 */
public final class KnowledgeSearchTool extends AbstractTool {

    public static final String NAME = "search_knowledge";

    private final KnowledgeBase knowledgeBase;
    private final int defaultTopK;
    private final com.aia.core.SingleFlight<String, List<SearchHit>> flight;

    public KnowledgeSearchTool(KnowledgeBase knowledgeBase, int defaultTopK) {
        this(knowledgeBase, defaultTopK, null);
    }

    /**
     * @param flight 并发去重：同一问题被同时问爆时只检索一次，可为 null
     */
    public KnowledgeSearchTool(KnowledgeBase knowledgeBase, int defaultTopK,
                               com.aia.core.SingleFlight<String, List<SearchHit>> flight) {
        this.knowledgeBase = knowledgeBase;
        this.defaultTopK = defaultTopK;
        this.flight = flight;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "检索内部知识库，返回与问题最相关的知识片段。回答产品、政策、流程等事实性问题前应先调用本工具。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("type", "string");
        query.put("description", "要检索的问题或关键词");

        Map<String, Object> topK = new LinkedHashMap<>();
        topK.put("type", "integer");
        topK.put("description", "返回的片段数量，默认 " + defaultTopK);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", query);
        properties.put("topK", topK);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        return schema;
    }

    @Override
    protected ToolResult doExecute(ToolInvocation invocation) {
        Object rawQuery = invocation.arguments().get("query");
        if (rawQuery == null || String.valueOf(rawQuery).isBlank()) {
            return ToolResult.error("缺少必填参数: query");
        }
        int topK = topK(invocation.arguments().get("topK"));

        String query = String.valueOf(rawQuery);
        List<SearchHit> hits = flight == null
                ? knowledgeBase.search(query, topK)
                : flight.execute(topK + "|" + QuestionNormalizer.normalize(query),
                        () -> knowledgeBase.search(query, topK));
        if (hits.isEmpty()) {
            return ToolResult.ok("未找到相关知识。");
        }
        StringBuilder sb = new StringBuilder("知识库检索结果：\n");
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            sb.append('[').append(i + 1).append("] (").append(hit.id()).append(") ")
                    .append(hit.text()).append('\n');
        }
        return ToolResult.ok(sb.toString().trim());
    }

    private int topK(Object rawTopK) {
        if (rawTopK instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (rawTopK != null) {
            try {
                return Math.max(1, Integer.parseInt(String.valueOf(rawTopK)));
            } catch (NumberFormatException ignored) {
                return defaultTopK;
            }
        }
        return defaultTopK;
    }
}
