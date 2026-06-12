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
import dev.javalitellm.core.spi.Capability;
import dev.javalitellm.core.spi.LlmProvider;
import dev.javalitellm.core.spi.ProviderConfig;
import dev.javalitellm.core.spi.StreamHandler;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI adapter. Also reaches any OpenAI-compatible endpoint (DeepSeek, Groq, Ollama, vLLM, ...)
 * via {@link ProviderConfig#apiBase()} override.
 */
public final class OpenAiProvider implements LlmProvider {

    public static final String DEFAULT_API_BASE = "https://api.openai.com/v1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiTransformer transformer = new OpenAiTransformer(mapper);
    private final HttpTransport http = new HttpTransport();

    @Override
    public String name() {
        return "openai";
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

    @Override
    public ChatResponse chat(ChatRequest request, ProviderConfig config) {
        ObjectNode wire = transformer.toWire(request, request.model(), false);
        HttpTransport.Response response = http.postJson(
                endpoint(config, "/chat/completions"),
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
        ObjectNode wire = transformer.toWire(request, request.model(), true);
        try {
            HttpTransport.Response error = http.postSse(
                    endpoint(config, "/chat/completions"),
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
                endpoint(config, "/embeddings"),
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

    private URI endpoint(ProviderConfig config, String path) {
        String base = config.apiBase() != null ? config.apiBase() : DEFAULT_API_BASE;
        return URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path);
    }

    private Map<String, String> headers(ProviderConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (config.apiKey() != null) {
            headers.put("Authorization", "Bearer " + config.apiKey());
        }
        headers.putAll(config.extraHeaders());
        return headers;
    }

    private LiteLlmException mapError(HttpTransport.Response response, String model) {
        String message = response.body();
        try {
            JsonNode root = mapper.readTree(response.body());
            if (root.path("error").has("message")) {
                message = root.path("error").path("message").asText();
            }
        } catch (JsonProcessingException ignored) {
            // non-JSON error body: keep the raw text
        }
        return StatusErrorMapper.map(response.status(), message, name(), model);
    }

    private JsonNode readTree(String json, String model) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new LiteLlmException(
                    "invalid JSON from provider: " + e.getOriginalMessage(), name(), model, 0, false, e);
        }
    }
}
