package com.aia.rag.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 路由评测集：每道题标注期望分派到的专业 agent。
 *
 * @param expected        期望的 agent；为 null 表示应当判为无法归类
 * @param alsoAcceptable  同样可接受的方向（多意图交叉场景）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoutingCase(String query, String expected, List<String> alsoAcceptable) {

    public RoutingCase {
        alsoAcceptable = alsoAcceptable == null ? List.of() : List.copyOf(alsoAcceptable);
    }
}
