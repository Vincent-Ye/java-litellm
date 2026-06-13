package dev.javalitellm.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Choice;
import dev.javalitellm.core.chat.Content;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.chat.Role;
import dev.javalitellm.core.chat.Usage;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Redis round-trip via Testcontainers. Skipped automatically when Docker is unavailable so
 * the test suite stays green on developer machines without Docker; CI runners (with Docker) cover
 * the actual I/O. {@code @EnabledIfDocker} below is a static-method guard rather than an extension
 * to keep dependencies minimal.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisLlmCacheIT {

    private static GenericContainer<?> redis;
    private static RedisLlmCache cache;

    @BeforeAll
    static void start() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "docker not available, skipping redis tests");
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();
        String uri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
        cache = new RedisLlmCache(uri, Duration.ofSeconds(2), new ChatResponseCodec(new ObjectMapper()));
    }

    @AfterAll
    static void stop() {
        if (cache != null) {
            cache.close();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @Test
    void roundTripsThroughRedis() {
        ChatResponse response = new ChatResponse(
                "chatcmpl-1",
                "gpt-4o",
                42L,
                List.of(new Choice(
                        0, new Message(Role.ASSISTANT, List.of(Content.text("hello redis")), null, null), "stop")),
                new Usage(5, 3, null, null),
                new BigDecimal("0.00001"));

        cache.put("key-1", response);
        ChatResponse hit = cache.get("key-1").orElseThrow();

        assertThat(hit.firstText()).isEqualTo("hello redis");
        assertThat(hit.usage().totalTokens()).isEqualTo(8);
        assertThat(hit.costUsd()).isEqualByComparingTo("0.00001");
    }

    @Test
    void missesReturnEmpty() {
        assertThat(cache.get("never-set")).isEmpty();
    }

    @Test
    void expiresAfterTtl() throws InterruptedException {
        ChatResponse response = new ChatResponse(
                "id",
                "gpt-4o",
                0L,
                List.of(new Choice(0, new Message(Role.ASSISTANT, List.of(Content.text("temp")), null, null), "stop")),
                null,
                null);
        cache.put("ttl-test", response);
        assertThat(cache.get("ttl-test")).isPresent();
        Thread.sleep(2500);
        assertThat(cache.get("ttl-test")).isEmpty();
    }
}
