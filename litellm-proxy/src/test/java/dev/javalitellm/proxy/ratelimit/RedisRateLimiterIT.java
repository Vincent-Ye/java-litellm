package dev.javalitellm.proxy.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisRateLimiterIT {

    private static GenericContainer<?> redis;
    private static RedisRateLimiter limiter;

    @BeforeAll
    static void start() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable());
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();
        limiter = new RedisRateLimiter("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @AfterAll
    static void stop() {
        if (limiter != null) limiter.close();
        if (redis != null) redis.stop();
    }

    @Test
    void enforcesRpmAcrossSeparateLimiterInstances() {
        String scope = "rpm-test-" + System.nanoTime();
        // Second instance simulates a second proxy replica sharing the same Redis
        RedisRateLimiter other = new RedisRateLimiter("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        try {
            assertThat(limiter.tryAcquireRequest(scope, 2)).isTrue();
            assertThat(other.tryAcquireRequest(scope, 2)).isTrue();
            // both replicas combined have used the limit
            assertThat(limiter.tryAcquireRequest(scope, 2)).isFalse();
            assertThat(other.tryAcquireRequest(scope, 2)).isFalse();
        } finally {
            other.close();
        }
    }

    @Test
    void tokenWindowSumsAcrossInstances() {
        String scope = "tpm-test-" + System.nanoTime();
        RedisRateLimiter other = new RedisRateLimiter("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        try {
            limiter.recordTokens(scope, 500);
            other.recordTokens(scope, 600);
            assertThat(limiter.withinTokenLimit(scope, 2000)).isTrue();
            assertThat(other.withinTokenLimit(scope, 1000)).isFalse();
        } finally {
            other.close();
        }
    }

    @Test
    void nullLimitsMeanUnlimited() {
        String scope = "unlimited-" + System.nanoTime();
        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryAcquireRequest(scope, null)).isTrue();
        }
        assertThat(limiter.withinTokenLimit(scope, null)).isTrue();
    }
}
