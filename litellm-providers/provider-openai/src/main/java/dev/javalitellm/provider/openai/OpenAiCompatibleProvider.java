package dev.javalitellm.provider.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Usage;
import dev.javalitellm.core.embedding.EmbeddingRequest;
import dev.javalitellm.core.embedding.EmbeddingResponse;
import dev.javalitellm.core.exception.LiteLlmException;
import dev.javalitellm.core.http.HttpTransport;
import dev.javalitellm.core.http.StatusErrorMapper;
import dev.javalitellm.core.spi.LlmProvider;
import dev.javalitellm.core.spi.ProviderConfig;
import dev.javalitellm.core.spi.StreamHandler;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base for providers speaking the OpenAI chat-completions wire format. Subclasses customize the
 * endpoint scheme, auth headers and (via {@link #adjustWire}) wire-level differences — this is how
 * Azure OpenAI, Mistral and other compatible services reuse one transformer.
 */
public abstract class OpenAiCompatibleProvider implements LlmProvider {

    protected final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiTransformer transformer = new OpenAiTransformer(mapper);
    private final HttpTransport http = new HttpTransport();

    /** Base URL used when {@link ProviderConfig#apiBase()} is not set; null means apiBase is required. */
    protected abstract String defaultApiBase();

    protected URI chatEndpoint(ProviderConfig config, String model) {
        return URI.create(resolvedBase(config, model) + "/chat/completions");
    }

    protected URI embeddingsEndpoint(ProviderConfig config, String model) {
        return URI.create(resolvedBase(config, model) + "/embeddings");
    }

    protected Map<String, String> headers(ProviderConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (config.apiKey() != null) {
            headers.put("Authorization", "Bearer " + config.apiKey());
        }
        headers.putAll(config.extraHeaders());
        return headers;
    }

    /** Hook for wire-level tweaks (drop or rewrite fields) before sending. */
    protected ObjectNode adjustWire(ObjectNode wire) {
        return wire;
    }

    protected final String resolvedBase(ProviderConfig config, String model) {
        String base = config.apiBase() != null ? config.apiBase() : defaultApiBase();
        if (base == null) {
            throw new dev.javalitellm.core.exception.BadRequestException(
                    "provider '" + name() + "' requires an explicit apiBase", name(), model);
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public ChatResponse chat(ChatRequest request, ProviderConfig config) {
        ObjectNode wire = adjustWire(transformer.toWire(request, request.model(), false));
        HttpTransport.Response response = http.postJson(
                chatEndpoint(config, request.model()),
                headers(config),
                wire.toString(),
                config.timeout(),
                name(),
                request.model());
        if (response.status() / 100 != 2) {
            throw mapError(response, request.model());
        }
        return transformer.fromWire(readTree(response.body(), request.model()));
    }

    @Override
    public void chatStream(ChatRequest request, ProviderConfig config, StreamHandler handler) {
        ObjectNode wire = adjustWire(transformer.toWire(request, request.model(), true));
        try {
            HttpTransport.Response error = http.postSse(
                    chatEndpoint(config, request.model()),
                    headers(config),
                    wire.toString(),
                    config.timeout(),
                    name(),
                    request.model(),
                    payload -> {
                        ChatChunk chunk = transformer.chunkFromWire(readTree(payload, request.model()));
                        if (chunk != null) {
                            handler.onChunk(chunk);
                        }
                    });
            if (error != null) {
                throw mapError(error, request.model());
            }
            handler.onComplete();
        } catch (LiteLlmException e) {
            handler.onError(e);
        }
    }

    @Override
    public EmbeddingResponse embedding(EmbeddingRequest request, ProviderConfig config) {
        ObjectNode wire = mapper.createObjectNode();
        wire.put("model", request.model());
        request.input().forEach(wire.putArray("input")::add);
        if (request.dimensions() != null) {
            wire.put("dimensions", request.dimensions());
        }
        for (Map.Entry<String, Object> extra : request.extraParams().entrySet()) {
            wire.set(extra.getKey(), mapper.valueToTree(extra.getValue()));
        }

        HttpTransport.Response response = http.postJson(
                embeddingsEndpoint(config, request.model()),
                headers(config),
                wire.toString(),
                config.timeout(),
                name(),
                request.model());
        if (response.status() / 100 != 2) {
            throw mapError(response, request.model());
        }

        JsonNode root = readTree(response.body(), request.model());
        List<float[]> embeddings = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            JsonNode vector = item.path("embedding");
            float[] values = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                values[i] = (float) vector.get(i).asDouble();
            }
            embeddings.add(values);
        }
        JsonNode usageNode = root.path("usage");
        Usage usage = usageNode.isMissingNode()
                ? null
                : Usage.of(usageNode.path("prompt_tokens").asInt(), 0);
        return new EmbeddingResponse(root.path("model").asText(null), embeddings, usage, null);
    }

    protected final LiteLlmException mapError(HttpTransport.Response response, String model) {
        String message = response.body();
        try {
            JsonNode root = mapper.readTree(response.body());
            if (root.path("error").has("message")) {
                message = root.path("error").path("message").asText();
            } else if (root.has("message")) {
                message = root.path("message").asText();
            }
        } catch (JsonProcessingException ignored) {
            // non-JSON error body: keep the raw text
        }
        return StatusErrorMapper.map(response.status(), message, name(), model);
    }

    protected final JsonNode readTree(String json, String model) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new LiteLlmException(
                    "invalid JSON from provider: " + e.getOriginalMessage(), name(), model, 0, false, e);
        }
    }
}
