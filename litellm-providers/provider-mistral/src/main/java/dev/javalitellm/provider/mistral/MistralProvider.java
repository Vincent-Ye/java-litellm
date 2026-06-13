package dev.javalitellm.provider.mistral;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.spi.Capability;
import dev.javalitellm.provider.openai.OpenAiCompatibleProvider;
import java.util.Set;

/**
 * Mistral AI adapter. The API is OpenAI-compatible but validates fields strictly, so OpenAI-only
 * extras like {@code stream_options} are dropped (Mistral always reports usage on the final chunk).
 */
public final class MistralProvider extends OpenAiCompatibleProvider {

    public static final String DEFAULT_API_BASE = "https://api.mistral.ai/v1";

    @Override
    public String name() {
        return "mistral";
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
                Capability.EMBEDDING,
                Capability.STRUCTURED_OUTPUT);
    }

    @Override
    protected ObjectNode adjustWire(ObjectNode wire) {
        wire.remove("stream_options");
        return wire;
    }
}
