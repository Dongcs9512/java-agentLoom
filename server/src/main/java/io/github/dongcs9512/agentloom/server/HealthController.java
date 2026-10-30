package io.github.dongcs9512.agentloom.server;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 健康检查：给负载均衡/网关用。
 *
 * <p>收到关闭信号后把状态切成 draining，让上游把流量摘走；
 * 配合 {@code server.shutdown=graceful}，在途请求会正常跑完再退出。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final long startedAt = System.currentTimeMillis();

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        draining.set(true);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", draining.get() ? "draining" : "up");
        body.put("uptimeMs", System.currentTimeMillis() - startedAt);
        return body;
    }

    public boolean isDraining() {
        return draining.get();
    }
}
