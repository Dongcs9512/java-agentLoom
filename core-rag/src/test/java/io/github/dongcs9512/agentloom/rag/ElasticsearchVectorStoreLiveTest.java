package io.github.dongcs9512.agentloom.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 针对真实 Elasticsearch 的端到端冒烟验证，默认跳过（不影响常规测试套件）。
 * 运行方式:
 *   mvn test -Dtest=ElasticsearchVectorStoreLiveTest \
 *            -Daia.es.url=http://192.168.5.15:9200 \
 *            -Dsurefire.failIfNoSpecifiedTests=false
 */
class ElasticsearchVectorStoreLiveTest {

    private ElasticsearchVectorStore store;

    @BeforeEach
    void setUp() {
        String url = System.getProperty("agentloom.es.url");
        assumeTrue(url != null && !url.isBlank(), "未配置 aia.es.url，跳过真实 ES 冒烟测试");
        store = new ElasticsearchVectorStore(url, "agentloom-framework-smoke", 3, HttpClient.newHttpClient());
    }

    @Test
    void writeSearchFilterDeleteRoundTrip() {
        store.upsert(List.of(
                new VectorRecord("a", "苹果", new float[]{1f, 0f, 0f}, Map.of("docId", "g1")),
                new VectorRecord("b", "香蕉", new float[]{0f, 1f, 0f}, Map.of("docId", "g2")),
                new VectorRecord("c", "橙子", new float[]{0f, 0f, 1f}, Map.of("docId", "g2"))));

        List<SearchHit> hits = store.search(new VectorQuery(new float[]{1f, 0f, 0f}, 3, Map.of()));
        assertEquals(3, hits.size());
        assertEquals("a", hits.get(0).id());
        assertTrue(hits.get(0).score() > hits.get(1).score(), "最相似项应排在首位");

        List<SearchHit> filtered = store.search(new VectorQuery(new float[]{1f, 0f, 0f}, 3, Map.of("docId", "g1")));
        assertEquals(1, filtered.size());
        assertEquals("a", filtered.get(0).id());

        store.delete(List.of("a"));
        List<SearchHit> after = store.search(new VectorQuery(new float[]{1f, 0f, 0f}, 3, Map.of()));
        assertEquals(2, after.size());
        assertTrue(after.stream().noneMatch(h -> "a".equals(h.id())));
    }
}
