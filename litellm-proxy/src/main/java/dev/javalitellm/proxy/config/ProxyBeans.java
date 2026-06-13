package dev.javalitellm.proxy.config;

import dev.javalitellm.cache.CaffeineLlmCache;
import dev.javalitellm.client.LiteLlm;
import dev.javalitellm.client.RetryPolicy;
import dev.javalitellm.proxy.cache.ProxyCache;
import dev.javalitellm.proxy.ratelimit.InMemoryRateLimiter;
import dev.javalitellm.proxy.ratelimit.RateLimiter;
import dev.javalitellm.router.Router;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProxyBeans {

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
    public ProxyCache proxyCache(ProxyConfigLoader.LoadedConfig config) {
        return new ProxyCache(
                config.cacheEnabled()
                        ? new CaffeineLlmCache(Duration.ofSeconds(config.cacheTtlSeconds()), 10_000)
                        : null);
    }

    @Bean
    public RateLimiter rateLimiter() {
        return new InMemoryRateLimiter();
    }

    @Bean
    public LiteLlm liteLlm() {
        return LiteLlm.builder().retryPolicy(RetryPolicy.NONE).build();
    }

    @Bean
    public Router router(ProxyConfigLoader.LoadedConfig config, LiteLlm liteLlm) {
        return Router.builder()
                .deployments(config.deployments())
                .client(liteLlm)
                .build();
    }
}
