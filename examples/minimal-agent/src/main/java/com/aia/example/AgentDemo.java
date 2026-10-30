package com.aia.example;

import com.aia.core.AgentLoop;
import com.aia.core.ToolDef;
import com.aia.core.ToolParam;
import com.aia.core.ToolRegistry;
import com.aia.core.ToolScanner;
import com.aia.llm.ChatModel;
import com.aia.llm.openai.OpenAiCompatibleChatModel;

import java.net.http.HttpClient;

/**
 * 最小示例：一个 agent + 一个工具，不依赖 Spring。
 *
 * <pre>
 *   export AIA_API_KEY=sk-xxx
 *   mvn -pl examples/minimal-agent exec:java -Dexec.mainClass=com.aia.example.AgentDemo
 * </pre>
 */
public final class AgentDemo {

    /** 用注解定义一个工具，会被 ToolScanner 扫出来注册进 registry。 */
    public static class OrderTools {

        @ToolDef(name = "query_order", description = "根据订单号查询订单当前状态和预计送达时间")
        public String queryOrder(
                @ToolParam(description = "订单号，例如 A123", required = true) String orderId) {
            // 真实项目里这里调微服务或 HTTP 接口
            return "订单 " + orderId + " 已发货，预计明天下午送达";
        }
    }

    public static void main(String[] args) {
        String apiKey = System.getenv("AIA_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("请先设置环境变量 AIA_API_KEY（模型服务的 key）");
            System.out.println("可选：AIA_BASE_URL（默认 https://api.deepseek.com/v1）、AIA_MODEL（默认 deepseek-chat）");
            return;
        }
        String baseUrl = env("AIA_BASE_URL", "https://api.deepseek.com/v1");
        String modelName = env("AIA_MODEL", "deepseek-chat");

        ChatModel model = new OpenAiCompatibleChatModel(baseUrl, apiKey, modelName, HttpClient.newHttpClient());

        ToolRegistry tools = new ToolRegistry();
        new ToolScanner().scan(new OrderTools()).forEach(tools::register);

        AgentLoop loop = new AgentLoop(model, tools,
                "你是电商客服助手。涉及订单状态的问题必须先调用 query_order 查询，不要凭空回答。");

        String answer = loop.run("帮我查下订单 A123 到哪了");
        System.out.println("客服：" + answer);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
