package io.github.dongcs9512.agentloom.rag.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 评测数据集：一份知识文档 + 一组问题。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalDataset(
        String name,
        String note,
        List<String> sources,
        List<Document> documents,
        List<EvalCase> cases,
        List<DialogueCase> dialogues) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(String docId, String text) {
    }

    public EvalDataset {
        sources = sources == null ? List.of() : List.copyOf(sources);
        documents = documents == null ? List.of() : List.copyOf(documents);
        cases = cases == null ? List.of() : List.copyOf(cases);
        dialogues = dialogues == null ? List.of() : List.copyOf(dialogues);
    }

    public static EvalDataset load(Path path) {
        try {
            return new ObjectMapper().readValue(Files.readString(path), EvalDataset.class);
        } catch (IOException e) {
            throw new IllegalStateException("加载评测集失败: " + path, e);
        }
    }
}
