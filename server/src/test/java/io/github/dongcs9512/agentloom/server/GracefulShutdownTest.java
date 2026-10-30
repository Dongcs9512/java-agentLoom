package io.github.dongcs9512.agentloom.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 优雅下线验证：关闭上下文时，已经在处理中的请求应当正常跑完，而不是被切断。
 *
 * <p>这里用独立的 {@link SpringApplicationBuilder} 起一整套上下文再关掉，
 * 不用 {@code @SpringBootTest}——否则会关掉测试框架缓存的共享上下文，影响其它测试。
 */
class GracefulShutdownTest {

    @Configuration
    static class SlowEndpointConfig {
        @Bean
        SlowController slowController() {
            return new SlowController();
        }
    }

    @RestController
    static class SlowController {
        @GetMapping("/api/slow")
        public String slow() throws InterruptedException {
            Thread.sleep(3000);
            return "done";
        }
    }

    @Test
    void inFlightRequestCompletesDuringGracefulShutdown() throws Exception {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                AiAgentApplication.class, SlowEndpointConfig.class)
                .properties(
                        "server.port=0",
                        "server.shutdown=graceful",
                        "spring.lifecycle.timeout-per-shutdown-phase=10s",
                        "agentloom.rag.enabled=false",
                        "agentloom.session.enabled=false",
                        "agentloom.answer-cache.enabled=false",
                        "agentloom.demo.enabled=false",
                        "agentloom.eval.enabled=false",
                        "agentloom.scenarios.enabled=false")
                .run();

        try {
            int port = context.getEnvironment().getProperty("local.server.port", Integer.class);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/slow"))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            long start = System.currentTimeMillis();
            CompletableFuture<HttpResponse<String>> inFlight =
                    client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

            // 等请求真正进入处理阶段，再关闭上下文
            Thread.sleep(800);
            CompletableFuture.runAsync(context::close);

            HttpResponse<String> response = inFlight.get(30, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            assertEquals(200, response.statusCode(), "在途请求不应被掐断");
            assertEquals("done", response.body());
            assertTrue(elapsed >= 3000, "请求应完整跑完，实际耗时 " + elapsed + "ms");
        } finally {
            if (context.isActive()) {
                context.close();
            }
        }
    }
}
