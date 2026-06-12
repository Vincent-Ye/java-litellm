package dev.javalitellm.core.spi;

import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.embedding.EmbeddingRequest;
import dev.javalitellm.core.embedding.EmbeddingResponse;
import dev.javalitellm.core.exception.UnsupportedCapabilityException;
import java.util.Set;

/**
 * Adapter for one model provider, discovered via {@link java.util.ServiceLoader}.
 *
 * <p>Implementations transform the canonical request to the provider wire format, call the provider,
 * and transform the response back — mapping provider errors to the unified exception hierarchy.
 * Implementations must be stateless and thread-safe. The {@code model} fields in requests carry the
 * bare model name (route prefix already stripped).
 */
public interface LlmProvider {

    /** Route prefix, e.g. "anthropic" in "anthropic/claude-sonnet-4-6". Lowercase, unique. */
    String name();

    Set<Capability> capabilities();

    ChatResponse chat(ChatRequest request, ProviderConfig config);

    void chatStream(ChatRequest request, ProviderConfig config, StreamHandler handler);

    default EmbeddingResponse embedding(EmbeddingRequest request, ProviderConfig config) {
        throw new UnsupportedCapabilityException(name(), request.model(), Capability.EMBEDDING);
    }
}
