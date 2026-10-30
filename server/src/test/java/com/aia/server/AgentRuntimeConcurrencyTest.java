package com.aia.server;

import com.aia.core.AgentConfig;
import com.aia.core.ConcurrencyGuard;
import com.aia.core.GuardChain;
import com.aia.core.InMemoryConfigRepository;
import com.aia.core.ToolRegistry;
import com.aia.llm.ChatMessage;
import com.aia.llm.ChatModel;
import com.aia.llm.ChatModelCapability;
import com.aia.llm.ChatRequest;
import com.aia.llm.ChatResponse;
import com.aia.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeConcurrencyTest {

    /** 每次调用阻塞 300ms，用来制造"占满并发"的场景。 */
    static class SlowModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ChatResponse(ChatMessage.assistant("ok"), "stop", new Usage(1, 1));
        }

        @Override
        public Set<ChatModelCapability> capabilities() {
            return Set.of(ChatModelCapability.TOOL_CALLING);
        }
    }

    private AgentRuntime runtime(ConcurrencyGuard guard) {
        InMemoryConfigRepository repo = new InMemoryConfigRepository();
        repo.saveAgent(new AgentConfig("a1", "sys", "m", List.of("*"), List.of(), null, null));
        return new AgentRuntime(repo, new ToolRegistry(), name -> new SlowModel(),
                new com.aia.core.DefaultToolResolver(), SessionSupport.disabled(),
                new GuardChain(List.of(guard)));
    }

    @Test
    void rejectsWhenConcurrencySaturated() throws Exception {
        ConcurrencyGuard guard = new ConcurrencyGuard("模型", 1, 0);
        AgentRuntime runtime = runtime(guard);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<String> running = pool.submit(() -> {
                started.countDown();
                return runtime.chat("a1", "第一条");
            });
            started.await(2, TimeUnit.SECONDS);
            Thread.sleep(100);
            long begin = System.nanoTime();
            assertThrows(IllegalStateException.class, () -> runtime.chat("a1", "第二条"));
            long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

            assertTrue(elapsedMs < 250, "饱和时应快速失败，实际耗时 " + elapsedMs + "ms");
            assertEquals("ok", running.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void permitRestoredAfterNormalCall() {
        ConcurrencyGuard guard = new ConcurrencyGuard("模型", 2, 0);
        AgentRuntime runtime = runtime(guard);

        runtime.chat("a1", "问题");
        runtime.chat("a1", "问题");

        assertEquals(2, guard.availablePermits(), "正常调用结束后许可必须全部归还");
    }
}
