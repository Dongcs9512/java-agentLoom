package io.github.dongcs9512.agentloom.rag.redis;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 订阅缓存失效频道：任何一个实例写入知识库后，其余实例立刻清掉自己的本地缓存，
 * 把版本号轮询的陈旧窗口从"秒级"压到"毫秒级"。
 *
 * <p>订阅使用独立的连接和线程，断开后自动重连；版本号轮询仍保留作为兜底。
 */
public final class RedisInvalidationSubscriber implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RedisInvalidationSubscriber.class.getName());

    private final String host;
    private final int port;
    private final String password;
    private final String channel;
    private final Runnable onInvalidate;
    private final long reconnectDelayMs;

    private volatile boolean running;
    private volatile JedisPubSub pubSub;
    private Thread thread;

    public RedisInvalidationSubscriber(String host, int port, String password, String channel,
                                       Runnable onInvalidate) {
        this(host, port, password, channel, onInvalidate, 2000);
    }

    public RedisInvalidationSubscriber(String host, int port, String password, String channel,
                                       Runnable onInvalidate, long reconnectDelayMs) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.channel = channel;
        this.onInvalidate = onInvalidate;
        this.reconnectDelayMs = Math.max(200, reconnectDelayMs);
    }

    public void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "agentloom-cache-invalidation");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            Jedis jedis = null;
            try {
                jedis = new Jedis(new HostAndPort(host, port),
                        DefaultJedisClientConfig.builder().password(password).build());
                pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String ch, String message) {
                        try {
                            onInvalidate.run();
                        } catch (RuntimeException e) {
                            LOG.log(Level.WARNING, "处理缓存失效消息失败", e);
                        }
                    }
                };
                jedis.subscribe(pubSub, channel);
            } catch (RuntimeException e) {
                if (running) {
                    LOG.log(Level.WARNING, "缓存失效订阅断开，稍后重连: " + e.getMessage());
                }
            } finally {
                if (jedis != null) {
                    try {
                        jedis.close();
                    } catch (RuntimeException ignored) {
                        // 忽略关闭异常
                    }
                }
            }
            if (running) {
                try {
                    Thread.sleep(reconnectDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        JedisPubSub current = pubSub;
        if (current != null) {
            try {
                current.unsubscribe();
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
