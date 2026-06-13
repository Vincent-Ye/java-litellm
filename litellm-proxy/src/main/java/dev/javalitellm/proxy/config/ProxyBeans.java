package dev.javalitellm.proxy.config;

import dev.javalitellm.client.LiteLlm;
import dev.javalitellm.client.RetryPolicy;
import dev.javalitellm.router.Router;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProxyBeans {

    @Bean
    public ProxyConfigLoader.LoadedConfig loadedConfig(@Value("${litellm.config:config.yaml}") String configPath) {
        return new ProxyConfigLoader().load(Path.of(configPath));
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
