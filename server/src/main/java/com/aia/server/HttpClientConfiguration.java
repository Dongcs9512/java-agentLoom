package com.aia.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 全局共享的 HttpClient。
 *
 * <p>每次请求都 new 一个 HttpClient 等于每次模型调用都重建连接池、重新做 TCP/TLS 握手，
 * 高并发下既增加几十毫秒延迟，又制造大量 TIME_WAIT 连接。这里统一复用一个实例。
 */
@Configuration
public class HttpClientConfiguration {

    @Value("${aia.http.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Bean
    public HttpClient sharedHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
