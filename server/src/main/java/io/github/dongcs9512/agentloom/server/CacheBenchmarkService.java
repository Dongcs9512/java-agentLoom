package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.rag.EmbeddingModel;
import io.github.dongcs9512.agentloom.rag.eval.CacheHitSimulator;
import io.github.dongcs9512.agentloom.rag.eval.CacheSimulation;
import io.github.dongcs9512.agentloom.rag.eval.CacheTrafficDataset;
import io.github.dongcs9512.agentloom.rag.eval.CacheTrafficItem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 评估答案缓存在高并发下的收益：按「热门问题 vs 个案描述」的混合流量，
 * 分别模拟精确缓存与不同阈值的语义缓存，给出命中率、精度与误命中情况。
 */
@Service
@ConditionalOnProperty(prefix = "agentloom.eval", name = "enabled", havingValue = "true")
public class CacheBenchmarkService {

    private static final double[] SEMANTIC_THRESHOLDS = {0.75, 0.80, 0.85, 0.90, 0.95};
    private static final int EMBED_BATCH = 16;

    private final ObjectProvider<EmbeddingModel> embeddingModel;

    @Value("${agentloom.eval.dataset-dir:evals}")
    private String datasetDir;

    public CacheBenchmarkService(ObjectProvider<EmbeddingModel> embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public CacheTrafficDataset load(String datasetName) {
        String file = datasetName.endsWith(".json") ? datasetName : datasetName + ".json";
        return CacheTrafficDataset.load(Path.of(datasetDir, file));
    }

    public Map<String, Object> run(String datasetName, Integer requests, Integer hotPercent, Integer seed) {
        CacheTrafficDataset dataset = load(datasetName);
        int total = requests == null || requests <= 0 ? 300 : requests;
        int hot = hotPercent == null ? 55 : Math.max(0, Math.min(100, hotPercent));
        List<CacheTrafficItem> traffic = buildTraffic(dataset, total, hot, seed == null ? 42 : seed);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataset", dataset.name());
        result.put("requests", traffic.size());
        result.put("hotPercent", hot);
        result.put("faqCount", dataset.faqs().size());
        result.put("tailCount", dataset.tail().size());

        CacheSimulation exact = CacheHitSimulator.simulateExact(traffic, Math.max(64, total));
        result.put("exact", exact);

        EmbeddingModel embedder = embeddingModel.getIfAvailable();
        if (embedder == null) {
            result.put("semantic", List.of());
            result.put("note", "未启用向量化模型，只评估了精确缓存");
            return result;
        }
        Map<String, float[]> vectors = embedAll(embedder, traffic);
        List<CacheSimulation> semantic = new ArrayList<>();
        for (double threshold : SEMANTIC_THRESHOLDS) {
            semantic.add(CacheHitSimulator.simulateSemantic(traffic, vectors, threshold, Math.max(64, total)));
        }
        result.put("semantic", semantic);
        return result;
    }

    /**
     * 热门问题按「很多人问同一句」采样（允许重复），
     * 个案描述按「每个用户说的都不一样」采样（尽量不重复），这样才贴近真实流量。
     */
    List<CacheTrafficItem> buildTraffic(CacheTrafficDataset dataset, int total, int hotPercent, int seed) {
        List<CacheTrafficItem> hotPool = new ArrayList<>();
        for (CacheTrafficDataset.Faq faq : dataset.faqs()) {
            for (String variant : faq.variants()) {
                hotPool.add(new CacheTrafficItem(faq.id(), variant));
            }
        }
        List<CacheTrafficItem> tailPool = new ArrayList<>();
        for (int i = 0; i < dataset.tail().size(); i++) {
            tailPool.add(new CacheTrafficItem("tail-" + i, dataset.tail().get(i)));
        }

        Random random = new Random(seed);
        List<CacheTrafficItem> traffic = new ArrayList<>(total);
        int tailCursor = 0;
        for (int i = 0; i < total; i++) {
            boolean useHot = !hotPool.isEmpty() && (tailPool.isEmpty() || random.nextInt(100) < hotPercent);
            if (useHot) {
                traffic.add(hotPool.get(random.nextInt(hotPool.size())));
            } else if (tailCursor < tailPool.size()) {
                traffic.add(tailPool.get(tailCursor++));
            } else {
                // 个案超出样本量时补一条措辞不同的，保证不会一字不差地重复
                CacheTrafficItem base = tailPool.get(tailCursor % tailPool.size());
                traffic.add(new CacheTrafficItem("tail-" + tailCursor,
                        base.text() + "（第 " + tailCursor + " 位咨询者补充）"));
                tailCursor++;
            }
        }
        return traffic;
    }

    private Map<String, float[]> embedAll(EmbeddingModel embedder, List<CacheTrafficItem> traffic) {
        Set<String> unique = new LinkedHashSet<>();
        traffic.forEach(item -> unique.add(item.text()));
        List<String> texts = new ArrayList<>(unique);
        Map<String, float[]> vectors = new LinkedHashMap<>();
        for (int i = 0; i < texts.size(); i += EMBED_BATCH) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + EMBED_BATCH));
            List<float[]> embedded = embedder.embedBatch(batch);
            for (int j = 0; j < batch.size(); j++) {
                vectors.put(batch.get(j), embedded.get(j));
            }
        }
        return vectors;
    }
}
