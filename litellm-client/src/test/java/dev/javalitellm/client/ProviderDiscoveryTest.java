package dev.javalitellm.client;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javalitellm.core.spi.Capability;
import dev.javalitellm.core.spi.LlmProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/** Guards the SPI wiring: every Tier-1 provider must be discoverable and declare its capabilities. */
class ProviderDiscoveryTest {

    @Test
    void allTierOneProvidersAreDiscoverableWithCapabilities() {
        Map<String, LlmProvider> providers = new HashMap<>();
        for (LlmProvider provider : ServiceLoader.load(LlmProvider.class)) {
            providers.put(provider.name(), provider);
        }

        assertThat(providers.keySet())
                .containsExactlyInAnyOrder("openai", "anthropic", "azure", "mistral", "gemini", "bedrock");

        providers.values().forEach(provider -> assertThat(provider.capabilities())
                .as("capabilities of %s", provider.name())
                .contains(Capability.CHAT, Capability.STREAMING, Capability.TOOLS));

        assertThat(providers.get("openai").capabilities()).contains(Capability.EMBEDDING, Capability.VISION);
        assertThat(providers.get("gemini").capabilities()).contains(Capability.EMBEDDING, Capability.VISION);
        assertThat(providers.get("bedrock").capabilities()).doesNotContain(Capability.EMBEDDING);
    }
}
