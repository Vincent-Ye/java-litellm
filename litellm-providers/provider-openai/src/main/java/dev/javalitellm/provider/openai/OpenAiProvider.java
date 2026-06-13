package dev.javalitellm.provider.openai;

import dev.javalitellm.core.spi.Capability;
import java.util.Set;

/**
 * OpenAI adapter. Also reaches any OpenAI-compatible endpoint (DeepSeek, Groq, Ollama, vLLM, ...)
 * via {@link dev.javalitellm.core.spi.ProviderConfig#apiBase()} override.
 */
public final class OpenAiProvider extends OpenAiCompatibleProvider {

    public static final String DEFAULT_API_BASE = "https://api.openai.com/v1";

    @Override
    public String name() {
        return "openai";
    }

    @Override
    protected String defaultApiBase() {
        return DEFAULT_API_BASE;
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(
                Capability.CHAT,
                Capability.STREAMING,
                Capability.TOOLS,
                Capability.VISION,
                Capability.EMBEDDING,
                Capability.STRUCTURED_OUTPUT);
    }
}
