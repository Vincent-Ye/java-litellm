package dev.javalitellm.cache;

import dev.javalitellm.core.chat.ChatResponse;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.Optional;

/**
 * Distributed exact-match cache backed by Redis. TTL is enforced server-side via {@code SET ... EX}.
 * The connection is shared per instance (Lettuce connections are thread-safe).
 */
public final class RedisLlmCache implements LlmCache, AutoCloseable {

    private static final String KEY_PREFIX = "litellm:cache:";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final ChatResponseCodec codec;
    private final Duration ttl;

    public RedisLlmCache(String redisUri, Duration ttl, ChatResponseCodec codec) {
        this(RedisClient.create(redisUri), ttl, codec);
    }

    public RedisLlmCache(RedisClient client, Duration ttl, ChatResponseCodec codec) {
        this.client = client;
        this.connection = client.connect();
        this.codec = codec;
        this.ttl = ttl;
    }

    @Override
    public Optional<ChatResponse> get(String key) {
        String value = connection.sync().get(KEY_PREFIX + key);
        return value == null ? Optional.empty() : Optional.of(codec.decode(value));
    }

    @Override
    public void put(String key, ChatResponse response) {
        RedisCommands<String, String> cmd = connection.sync();
        cmd.setex(KEY_PREFIX + key, ttl.toSeconds(), codec.encode(response));
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
