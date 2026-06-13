package dev.javalitellm.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisRouterStateStoreIT {

    private static GenericContainer<?> redis;
    private static RedisRouterStateStore store;

    @BeforeAll
    static void start() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable());
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();
        store = new RedisRouterStateStore("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @AfterAll
    static void stop() {
        if (store != null) store.close();
        if (redis != null) redis.stop();
    }

    @Test
    void cooldownIsVisibleAcrossInstances() {
        String dep = "dep-cool-" + System.nanoTime();
        RedisRouterStateStore other =
                new RedisRouterStateStore("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        try {
            store.startCooldown(dep, Duration.ofSeconds(2));
            assertThat(other.isCoolingDown(dep)).isTrue();
        } finally {
            other.close();
        }
    }

    @Test
    void cooldownExpires() throws InterruptedException {
        String dep = "dep-exp-" + System.nanoTime();
        store.startCooldown(dep, Duration.ofMillis(200));
        assertThat(store.isCoolingDown(dep)).isTrue();
        Thread.sleep(400);
        assertThat(store.isCoolingDown(dep)).isFalse();
    }

    @Test
    void failureCountAndSuccessReset() {
        String dep = "dep-fail-" + System.nanoTime();
        store.recordFailure(dep);
        store.recordFailure(dep);
        store.recordFailure(dep);
        assertThat(store.consecutiveFailures(dep)).isEqualTo(3);
        store.recordSuccess(dep, 100, 50);
        assertThat(store.consecutiveFailures(dep)).isZero();
    }

    @Test
    void slidingWindowAggregatesAcrossInstances() {
        String dep = "dep-window-" + System.nanoTime();
        RedisRouterStateStore other =
                new RedisRouterStateStore("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        try {
            store.recordSuccess(dep, 100, 50);
            other.recordSuccess(dep, 300, 70);
            assertThat(store.requestsLastMinute(dep)).isEqualTo(2);
            assertThat(other.tokensLastMinute(dep)).isEqualTo(120);
            assertThat(store.averageLatencyMillis(dep)).hasValue(200.0);
        } finally {
            other.close();
        }
    }
}
