package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.core.session.AnswerCache;
import io.github.dongcs9512.agentloom.core.session.ChitchatMode;
import io.github.dongcs9512.agentloom.core.session.CompositeAnswerCache;
import io.github.dongcs9512.agentloom.core.session.CompositeSessionStore;
import io.github.dongcs9512.agentloom.core.session.ContextCompressor;
import io.github.dongcs9512.agentloom.core.session.FallbackContextCompressor;
import io.github.dongcs9512.agentloom.core.session.InMemoryAnswerCache;
import io.github.dongcs9512.agentloom.core.session.InMemorySessionStore;
import io.github.dongcs9512.agentloom.core.session.ModelSelectiveContextCompressor;
import io.github.dongcs9512.agentloom.core.session.RetrievalRouter;
import io.github.dongcs9512.agentloom.core.session.RuleBasedRetrievalRouter;
import io.github.dongcs9512.agentloom.core.session.SessionStore;
import io.github.dongcs9512.agentloom.core.session.SummarizingContextCompressor;
import io.github.dongcs9512.agentloom.core.session.TruncatingContextCompressor;
import io.github.dongcs9512.agentloom.session.jdbc.JdbcAnswerCache;
import io.github.dongcs9512.agentloom.session.jdbc.JdbcSessionStore;
import io.github.dongcs9512.agentloom.session.redis.RedisAnswerCache;
import io.github.dongcs9512.agentloom.session.redis.RedisSessionStore;
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
@ConditionalOnProperty(prefix = "agentloom.session", name = "enabled", havingValue = "true")
public class SessionConfiguration {

    @Value("${agentloom.session.store:memory}")
    private String sessionStoreType;

    @Value("${agentloom.session.ttl-seconds:604800}")
    private int sessionTtlSeconds;

    @Value("${agentloom.session.redis.host:}")
    private String redisHost;

    @Value("${agentloom.session.redis.port:6379}")
    private int redisPort;

    @Value("${agentloom.session.redis.password:}")
    private String redisPassword;

    @Value("${agentloom.session.redis.key-prefix:aia:session:}")
    private String redisKeyPrefix;

    @Value("${agentloom.answer-cache.enabled:false}")
    private boolean answerCacheEnabled;

    @Value("${agentloom.answer-cache.store:memory}")
    private String answerCacheStoreType;

    @Value("${agentloom.answer-cache.capacity:2000}")
    private int answerCacheCapacity;

    @Value("${agentloom.answer-cache.ttl-seconds:3600}")
    private int answerCacheTtlSeconds;

    @Value("${agentloom.answer-cache.redis.host:}")
    private String answerCacheRedisHost;

    @Value("${agentloom.answer-cache.redis.port:6379}")
    private int answerCacheRedisPort;

    @Value("${agentloom.answer-cache.redis.password:}")
    private String answerCacheRedisPassword;

    @Value("${agentloom.answer-cache.redis.key-prefix:aia:answer:}")
    private String answerCacheRedisKeyPrefix;

    @Value("${agentloom.router.enabled:true}")
    private boolean routerEnabled;

    @Value("${agentloom.router.chitchat-reply:您好，请问有什么可以帮您？}")
    private String chitchatReply;

    @Value("${agentloom.router.chitchat-mode:canned}")
    private String chitchatMode;

    @Value("${agentloom.router.extra-chitchat:}")
    private String extraChitchat;

    @Value("${agentloom.compression.enabled:true}")
    private boolean compressionEnabled;

    @Value("${agentloom.compression.strategy:summarize}")
    private String compressionStrategy;

    @Value("${agentloom.compression.threshold-tokens:3000}")
    private int compressionThresholdTokens;

    @Value("${agentloom.compression.keep-recent-messages:8}")
    private int compressionKeepRecent;

    @Value("${agentloom.compression.model:deepseek-chat}")
    private String compressionModel;

    private final Executor archiveExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "agentloom-archive");
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
