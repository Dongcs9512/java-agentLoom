package com.aia.rag.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RoutingDataset(String name, String note, List<Scenario> scenarios, List<RoutingCase> cases) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scenario(String agent, String description, List<String> examples) {
        public Scenario {
            examples = examples == null ? List.of() : List.copyOf(examples);
        }
    }

    public RoutingDataset {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public static RoutingDataset load(Path path) {
        try {
            return new ObjectMapper().readValue(Files.readString(path), RoutingDataset.class);
        } catch (IOException e) {
            throw new IllegalStateException("加载路由评测集失败: " + path, e);
        }
    }
}
