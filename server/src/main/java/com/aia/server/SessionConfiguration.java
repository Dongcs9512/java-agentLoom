package com.aia.server;

import com.aia.core.session.AnswerCache;
import com.aia.core.session.ChitchatMode;
import com.aia.core.session.CompositeAnswerCache;
import com.aia.core.session.CompositeSessionStore;
import com.aia.core.session.ContextCompressor;
import com.aia.core.session.FallbackContextCompressor;
import com.aia.core.session.InMemoryAnswerCache;
import com.aia.core.session.InMemorySessionStore;
import com.aia.core.session.ModelSelectiveContextCompressor;
import com.aia.core.session.RetrievalRouter;
import com.aia.core.session.RuleBasedRetrievalRouter;
import com.aia.core.session.SessionStore;
import com.aia.core.session.SummarizingContextCompressor;
import com.aia.core.session.TruncatingContextCompressor;
import com.aia.session.jdbc.JdbcAnswerCache;
import com.aia.session.jdbc.JdbcSessionStore;
import com.aia.session.redis.RedisAnswerCache;
import com.aia.session.redis.RedisSessionStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 多轮会话相关装配：会话存储、答案缓存、检索路由、上下文压缩。
 * 只有 {@code aia.session.enabled=true} 时才生效。
 */
@Configuration
@ConditionalOnProperty(prefix = "aia.session", name = "enabled", havingValue = "true")
public class SessionConfiguration {

    @Value("${aia.session.store:memory}")
    private String sessionStoreType;

    @Value("${aia.session.ttl-seconds:604800}")
    private int sessionTtlSeconds;

    @Value("${aia.session.redis.host:}")
    private String redisHost;

    @Value("${aia.session.redis.port:6379}")
    private int redisPort;

    @Value("${aia.session.redis.password:}")
    private String redisPassword;

    @Value("${aia.session.redis.key-prefix:aia:session:}")
    private String redisKeyPrefix;

    @Value("${aia.answer-cache.enabled:false}")
    private boolean answerCacheEnabled;

    @Value("${aia.answer-cache.store:memory}")
    private String answerCacheStoreType;

    @Value("${aia.answer-cache.capacity:2000}")
    private int answerCacheCapacity;

    @Value("${aia.answer-cache.ttl-seconds:3600}")
    private int answerCacheTtlSeconds;

    @Value("${aia.answer-cache.redis.host:}")
    private String answerCacheRedisHost;

    @Value("${aia.answer-cache.redis.port:6379}")
    private int answerCacheRedisPort;

    @Value("${aia.answer-cache.redis.password:}")
    private String answerCacheRedisPassword;

    @Value("${aia.answer-cache.redis.key-prefix:aia:answer:}")
    private String answerCacheRedisKeyPrefix;

    @Value("${aia.router.enabled:true}")
    private boolean routerEnabled;

    @Value("${aia.router.chitchat-reply:您好，请问有什么可以帮您？}")
    private String chitchatReply;

    @Value("${aia.router.chitchat-mode:canned}")
    private String chitchatMode;

    @Value("${aia.router.extra-chitchat:}")
    private String extraChitchat;

    @Value("${aia.compression.enabled:true}")
    private boolean compressionEnabled;

    @Value("${aia.compression.strategy:summarize}")
    private String compressionStrategy;

    @Value("${aia.compression.threshold-tokens:3000}")
    private int compressionThresholdTokens;

    @Value("${aia.compression.keep-recent-messages:8}")
    private int compressionKeepRecent;

    @Value("${aia.compression.model:deepseek-chat}")
    private String compressionModel;

    private final Executor archiveExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "aia-archive");
        t.setDaemon(true);
        return t;
    });

    @Bean
    public SessionSupport sessionSupport(ObjectProvider<DataSource> dataSourceProvider,
                                         ChatModelFactory chatModelFactory) {
        return new SessionSupport(
                buildSessionStore(dataSourceProvider),
                buildAnswerCache(dataSourceProvider),
                buildRouter(),
                buildCompressor(chatModelFactory));
    }

    private SessionStore buildSessionStore(ObjectProvider<DataSource> dataSourceProvider) {
        String type = normalized(sessionStoreType);
        return switch (type) {
            case "memory" -> new InMemorySessionStore();
            case "redis" -> redisSessionStore();
            case "jdbc", "mysql" -> new JdbcSessionStore(requireDataSource(dataSourceProvider));
            case "composite" -> new CompositeSessionStore(redisSessionStore(),
                    new JdbcSessionStore(requireDataSource(dataSourceProvider)), archiveExecutor);
            default -> throw new IllegalStateException(
                    "未知的会话存储类型: " + sessionStoreType + "，可选 memory / redis / jdbc / composite");
        };
    }

    private RedisSessionStore redisSessionStore() {
        if (redisHost.isBlank()) {
            throw new IllegalStateException("会话存储选用 redis 时必须配置 aia.session.redis.host");
        }
        return new RedisSessionStore(redisHost, redisPort, redisPassword, redisKeyPrefix, sessionTtlSeconds);
    }

    private AnswerCache buildAnswerCache(ObjectProvider<DataSource> dataSourceProvider) {
        if (!answerCacheEnabled) {
            return null;
        }
        String type = normalized(answerCacheStoreType);
        return switch (type) {
            case "memory" -> new InMemoryAnswerCache(answerCacheCapacity);
            case "redis" -> redisAnswerCache();
            case "jdbc", "mysql" -> new JdbcAnswerCache(requireDataSource(dataSourceProvider));
            case "composite" -> new CompositeAnswerCache(redisAnswerCache(),
                    new JdbcAnswerCache(requireDataSource(dataSourceProvider)), archiveExecutor);
            default -> throw new IllegalStateException(
                    "未知的答案缓存类型: " + answerCacheStoreType + "，可选 memory / redis / jdbc / composite");
        };
    }

    private RedisAnswerCache redisAnswerCache() {
        String host = answerCacheRedisHost.isBlank() ? redisHost : answerCacheRedisHost;
        String password = answerCacheRedisPassword.isBlank() ? redisPassword : answerCacheRedisPassword;
        if (host.isBlank()) {
            throw new IllegalStateException("答案缓存选用 redis 时必须配置 aia.answer-cache.redis.host");
        }
        return new RedisAnswerCache(host, answerCacheRedisPort, password, answerCacheRedisKeyPrefix,
                answerCacheTtlSeconds);
    }

    private RetrievalRouter buildRouter() {
        if (!routerEnabled) {
            return null;
        }
        List<String> extra = extraChitchat == null || extraChitchat.isBlank()
                ? List.of()
                : Arrays.stream(extraChitchat.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        ChitchatMode mode = "model".equals(normalized(chitchatMode)) ? ChitchatMode.MODEL : ChitchatMode.CANNED;
        return new RuleBasedRetrievalRouter(chitchatReply, extra, mode);
    }

    private ContextCompressor buildCompressor(ChatModelFactory chatModelFactory) {
        if (!compressionEnabled) {
            return null;
        }
        TruncatingContextCompressor truncate =
                new TruncatingContextCompressor(compressionThresholdTokens, compressionKeepRecent);
        return switch (normalized(compressionStrategy)) {
            case "truncate" -> truncate;
            case "model-select" -> new FallbackContextCompressor(
                    new ModelSelectiveContextCompressor(chatModelFactory.create(compressionModel),
                            compressionThresholdTokens, compressionKeepRecent), truncate);
            case "summarize", "" -> new FallbackContextCompressor(
                    new SummarizingContextCompressor(chatModelFactory.create(compressionModel),
                            compressionThresholdTokens, compressionKeepRecent), truncate);
            default -> throw new IllegalStateException(
                    "未知的压缩策略: " + compressionStrategy + "，可选 summarize / model-select / truncate");
        };
    }

    private DataSource requireDataSource(ObjectProvider<DataSource> provider) {
        DataSource dataSource = provider.getIfAvailable();
        if (dataSource == null) {
            throw new IllegalStateException(
                    "会话或缓存选用 jdbc/composite 时需先开启 aia.config.mode=jdbc 提供数据源");
        }
        return dataSource;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
