package dev.javalitellm.client;

import dev.javalitellm.core.exception.BadRequestException;
import dev.javalitellm.core.spi.LlmProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/** Providers discovered from the classpath via {@link ServiceLoader}. */
final class ProviderRegistry {

    private final Map<String, LlmProvider> providers;

    private ProviderRegistry(Map<String, LlmProvider> providers) {
        this.providers = providers;
    }

    static ProviderRegistry discover() {
        Map<String, LlmProvider> found = new LinkedHashMap<>();
        for (LlmProvider provider : ServiceLoader.load(LlmProvider.class)) {
            found.put(provider.name(), provider);
        }
        return new ProviderRegistry(found);
    }

    LlmProvider require(String name, String model) {
        LlmProvider provider = providers.get(name);
        if (provider == null) {
            throw new BadRequestException(
                    "no provider '" + name + "' on the classpath (known: " + providers.keySet() + ")", name, model);
        }
        return provider;
    }
}
