package com.aia.rag.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 缓存流量数据集：重复率高的「热门问题」+ 互不相同的「个案描述」。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CacheTrafficDataset(String name, String note, List<Faq> faqs, List<String> tail) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Faq(String id, String canonical, List<String> variants) {
        public Faq {
            variants = variants == null ? List.of() : List.copyOf(variants);
        }
    }

    public CacheTrafficDataset {
        faqs = faqs == null ? List.of() : List.copyOf(faqs);
        tail = tail == null ? List.of() : List.copyOf(tail);
    }

    public static CacheTrafficDataset load(Path path) {
        try {
            return new ObjectMapper().readValue(Files.readString(path), CacheTrafficDataset.class);
        } catch (IOException e) {
            throw new IllegalStateException("加载缓存流量集失败: " + path, e);
        }
    }
}
