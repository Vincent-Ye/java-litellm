package dev.javalitellm.proxy.ratelimit;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Sliding-window rate limiter backed by Redis. Each scope has two sorted sets keyed by timestamp:
 * one counts requests, the other accumulates token usage. A Lua script atomically prunes the
 * one-minute window, checks the limit and inserts the new entry — so multiple proxy replicas share
 * one accurate view.
 */
public final class RedisRateLimiter implements RateLimiter, AutoCloseable {

    private static final String REQUEST_PREFIX = "litellm:rl:req:";
    private static final String TOKEN_PREFIX = "litellm:rl:tok:";
    private static final long WINDOW_MILLIS = 60_000L;

    private static final String REQUEST_SCRIPT = "local now = tonumber(ARGV[1]); local limit = tonumber(ARGV[2]); "
            + "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - "
            + WINDOW_MILLIS
            + "); "
            + "local count = redis.call('ZCARD', KEYS[1]); "
            + "if count >= limit then return 0 end; "
            + "redis.call('ZADD', KEYS[1], now, now .. ':' .. ARGV[3]); "
            + "redis.call('PEXPIRE', KEYS[1], "
            + WINDOW_MILLIS
            + "); "
            + "return 1";

    private static final String TOKEN_SUM_SCRIPT = "local now = tonumber(ARGV[1]); "
            + "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - "
            + WINDOW_MILLIS
            + "); "
            + "local entries = redis.call('ZRANGE', KEYS[1], 0, -1); "
            + "local sum = 0; "
            + "for _, v in ipairs(entries) do local _, tokens = string.match(v, '(%d+):(%d+):.*'); "
            + "  sum = sum + (tonumber(tokens) or 0); end; "
            + "return sum";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final boolean ownsClient;
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    public RedisRateLimiter(String redisUri) {
        this(RedisClient.create(redisUri), true);
    }

    public RedisRateLimiter(RedisClient client, boolean ownsClient) {
        this.client = client;
        this.connection = client.connect();
        this.ownsClient = ownsClient;
    }

    @Override
    public boolean tryAcquireRequest(String scope, Integer rpmLimit) {
        if (rpmLimit == null) {
            return true;
        }
        RedisCommands<String, String> cmd = connection.sync();
        Long result = cmd.eval(
                REQUEST_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {REQUEST_PREFIX + scope},
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(rpmLimit),
                String.valueOf(random.nextLong()));
        return result != null && result == 1L;
    }

    @Override
    public boolean withinTokenLimit(String scope, Integer tpmLimit) {
        if (tpmLimit == null) {
            return true;
        }
        RedisCommands<String, String> cmd = connection.sync();
        Long sum = cmd.eval(
                TOKEN_SUM_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {TOKEN_PREFIX + scope},
                String.valueOf(System.currentTimeMillis()));
        return sum == null || sum < tpmLimit;
    }

    @Override
    public void recordTokens(String scope, int tokens) {
        if (tokens <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        RedisCommands<String, String> cmd = connection.sync();
        cmd.zadd(TOKEN_PREFIX + scope, (double) now, now + ":" + tokens + ":" + random.nextLong());
        cmd.pexpire(TOKEN_PREFIX + scope, WINDOW_MILLIS);
    }

    @Override
    public void close() {
        connection.close();
        if (ownsClient) {
            client.shutdown();
        }
    }
}
