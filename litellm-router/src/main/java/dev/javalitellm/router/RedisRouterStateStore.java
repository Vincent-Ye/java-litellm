package dev.javalitellm.router;

import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Distributed router state for multi-replica proxy deployments. Cooldown and counters live in
 * Redis so a 429 on one replica cools the deployment down for all replicas. Latency, TPM/RPM
 * counters use sorted sets per deployment with a one-minute sliding window.
 *
 * <p>In-flight counts are inherently per-process (they reflect this replica's current load), so
 * those stay in memory — this matches what {@code least-busy} routing actually needs to know.
 */
public final class RedisRouterStateStore implements RouterStateStore, AutoCloseable {

    private static final String COOLDOWN_PREFIX = "litellm:router:cool:";
    private static final String FAIL_PREFIX = "litellm:router:fail:";
    private static final String SAMPLES_PREFIX = "litellm:router:samples:";
    private static final long WINDOW_MILLIS = 60_000L;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final boolean ownsClient;
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    public RedisRouterStateStore(String redisUri) {
        this(RedisClient.create(redisUri), true);
    }

    public RedisRouterStateStore(RedisClient client, boolean ownsClient) {
        this.client = client;
        this.connection = client.connect();
        this.ownsClient = ownsClient;
    }

    @Override
    public void recordSuccess(String deploymentId, long latencyMillis, int totalTokens) {
        RedisCommands<String, String> cmd = connection.sync();
        cmd.del(FAIL_PREFIX + deploymentId);
        long now = System.currentTimeMillis();
        cmd.zadd(
                SAMPLES_PREFIX + deploymentId,
                (double) now,
                now + ":" + latencyMillis + ":" + totalTokens + ":" + random.nextLong());
        cmd.pexpire(SAMPLES_PREFIX + deploymentId, WINDOW_MILLIS);
    }

    @Override
    public void recordFailure(String deploymentId) {
        connection.sync().incr(FAIL_PREFIX + deploymentId);
        connection.sync().expire(FAIL_PREFIX + deploymentId, 300);
    }

    @Override
    public int consecutiveFailures(String deploymentId) {
        String value = connection.sync().get(FAIL_PREFIX + deploymentId);
        return value == null ? 0 : Integer.parseInt(value);
    }

    @Override
    public void startCooldown(String deploymentId, Duration duration) {
        connection.sync().set(COOLDOWN_PREFIX + deploymentId, "1", SetArgs.Builder.px(duration.toMillis()));
    }

    @Override
    public boolean isCoolingDown(String deploymentId) {
        return connection.sync().exists(COOLDOWN_PREFIX + deploymentId) > 0;
    }

    // In-flight remains per-process: each replica's "current load" is local by definition.
    @Override
    public void incrementInFlight(String deploymentId) {
        inFlight.computeIfAbsent(deploymentId, k -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public void decrementInFlight(String deploymentId) {
        inFlight.computeIfAbsent(deploymentId, k -> new AtomicInteger()).decrementAndGet();
    }

    @Override
    public int inFlight(String deploymentId) {
        AtomicInteger counter = inFlight.get(deploymentId);
        return counter == null ? 0 : counter.get();
    }

    @Override
    public OptionalDouble averageLatencyMillis(String deploymentId) {
        return averageOfField(deploymentId, 1);
    }

    @Override
    public int tokensLastMinute(String deploymentId) {
        return sumOfField(deploymentId, 2);
    }

    @Override
    public int requestsLastMinute(String deploymentId) {
        return sampleCount(deploymentId);
    }

    private OptionalDouble averageOfField(String deploymentId, int fieldIndex) {
        List<String> samples = recentSamples(deploymentId);
        if (samples.isEmpty()) {
            return OptionalDouble.empty();
        }
        long total = 0;
        for (String sample : samples) {
            String[] parts = sample.split(":");
            total += Long.parseLong(parts[fieldIndex]);
        }
        return OptionalDouble.of(total / (double) samples.size());
    }

    private int sumOfField(String deploymentId, int fieldIndex) {
        int sum = 0;
        for (String sample : recentSamples(deploymentId)) {
            String[] parts = sample.split(":");
            sum += Integer.parseInt(parts[fieldIndex]);
        }
        return sum;
    }

    private int sampleCount(String deploymentId) {
        return recentSamples(deploymentId).size();
    }

    private List<String> recentSamples(String deploymentId) {
        long cutoff = System.currentTimeMillis() - WINDOW_MILLIS;
        connection.sync().zremrangebyscore(SAMPLES_PREFIX + deploymentId, 0, cutoff);
        return connection.sync().zrange(SAMPLES_PREFIX + deploymentId, 0, -1);
    }

    @Override
    public void close() {
        connection.close();
        if (ownsClient) {
            client.shutdown();
        }
    }
}
