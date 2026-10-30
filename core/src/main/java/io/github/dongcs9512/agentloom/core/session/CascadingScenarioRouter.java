package io.github.dongcs9512.agentloom.core.session;

import java.util.List;
import java.util.Optional;

/**
 * 级联路由：依次尝试每个策略，谁先给出结果就用谁。
 * 典型用法：关键词（0 成本、高置信）→ 向量语义（一次 embedding）→ LLM 分类（最准但最慢）。
 */
public final class CascadingScenarioRouter implements ScenarioRouter {

    private final List<ScenarioRouter> routers;

    public CascadingScenarioRouter(List<ScenarioRouter> routers) {
        this.routers = routers == null ? List.of() : List.copyOf(routers);
    }

    @Override
    public Optional<String> route(String text, Session session) {
        for (ScenarioRouter router : routers) {
            Optional<String> result = router.route(text, session);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    public List<ScenarioRouter> routers() {
        return routers;
    }
}
