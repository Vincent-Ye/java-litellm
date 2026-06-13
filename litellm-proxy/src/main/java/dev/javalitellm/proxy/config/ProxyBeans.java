package dev.javalitellm.proxy.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javalitellm.cache.CaffeineLlmCache;
import dev.javalitellm.cache.ChatResponseCodec;
import dev.javalitellm.cache.LlmCache;
import dev.javalitellm.cache.RedisLlmCache;
import dev.javalitellm.client.LiteLlm;
import dev.javalitellm.client.RetryPolicy;
import dev.javalitellm.proxy.cache.ProxyCache;
import dev.javalitellm.proxy.ratelimit.InMemoryRateLimiter;
import dev.javalitellm.proxy.ratelimit.RateLimiter;
import dev.javalitellm.proxy.ratelimit.RedisRateLimiter;
import dev.javalitellm.router.RedisRouterStateStore;
import dev.javalitellm.router.Router;
import io.lettuce.core.RedisClient;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProxyBeans {

    private static final Logger log = LoggerFactory.getLogger(ProxyBeans.class);

    /** Holder for the optional Redis client. Empty when no redis_url is configured. */
    public static final class RedisHolder {
        private final RedisClient client;

        RedisHolder(RedisClient client) {
            this.client = client;
        }

        public RedisClient get() {
            return client;
        }

        public boolean enabled() {
            return client != null;
        }

        @PreDestroy
        void shutdown() {
            if (client != null) {
                client.shutdown();
            }
        }
    }

    @Bean
    public ProxyConfigLoader proxyConfigLoader() {
        return new ProxyConfigLoader();
    }

    @Bean
    public ProxyConfigLoader.LoadedConfig loadedConfig(
            ProxyConfigLoader loader, @Value("${litellm.config:config.yaml}") String configPath) {
        return loader.load(Path.of(configPath));
    }

    @Bean
    public RedisHolder redisHolder(ProxyConfigLoader.LoadedConfig config) {
        if (config.redisUrl() == null || config.redisUrl().isBlank()) {
            return new RedisHolder(null);
        }
        log.info("connecting to Redis at {}", config.redisUrl());
        return new RedisHolder(RedisClient.create(config.redisUrl()));
    }

    @Bean
    public ProxyCache proxyCache(ProxyConfigLoader.LoadedConfig config, RedisHolder redis, ObjectMapper mapper) {
        if (!config.cacheEnabled()) {
            return new ProxyCache(null);
        }
        LlmCache delegate = redis.enabled()
                ? new RedisLlmCache(
                        redis.get(), Duration.ofSeconds(config.cacheTtlSeconds()), new ChatResponseCodec(mapper))
                : new CaffeineLlmCache(Duration.ofSeconds(config.cacheTtlSeconds()), 10_000);
        return new ProxyCache(delegate);
    }

    @Bean
    public RateLimiter rateLimiter(RedisHolder redis) {
        return redis.enabled() ? new RedisRateLimiter(redis.get(), false) : new InMemoryRateLimiter();
    }

    @Bean
    public LiteLlm liteLlm() {
        return LiteLlm.builder().retryPolicy(RetryPolicy.NONE).build();
    }

    @Bean
    public Router router(ProxyConfigLoader.LoadedConfig config, LiteLlm liteLlm, RedisHolder redis) {
        Router.Builder builder =
                Router.builder().deployments(config.deployments()).client(liteLlm);
        if (redis.enabled()) {
            builder.state(new RedisRouterStateStore(redis.get(), false));
        }
        return builder.build();
    }
}
