package dev.javalitellm.provider.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.exception.LiteLlmException;
import dev.javalitellm.core.http.HttpTransport;
import dev.javalitellm.core.http.StatusErrorMapper;
import dev.javalitellm.core.spi.Capability;
import dev.javalitellm.core.spi.LlmProvider;
import dev.javalitellm.core.spi.ProviderConfig;
import dev.javalitellm.core.spi.StreamHandler;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class AnthropicProvider implements LlmProvider {

    public static final String DEFAULT_API_BASE = "https://api.anthropic.com";
    private static final String API_VERSION = "2023-06-01";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicTransformer transformer = new AnthropicTransformer(mapper);
    private final HttpTransport http = new HttpTransport();

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.STREAMING, Capability.TOOLS, Capability.VISION);
    }

    @Override
    public ChatResponse chat(ChatRequest request, ProviderConfig config) {
        ObjectNode wire = transformer.toWire(request, request.model(), false);
        HttpTransport.Response response = http.postJson(
                endpoint(config), headers(config), wire.toString(), config.timeout(), name(), request.model());
        if (response.status() / 100 != 2) {
            throw mapError(response, request.model());
        }
        return transformer.fromWire(readTree(response.body(), request.model()));
    }

    @Override
    public void chatStream(ChatRequest request, ProviderConfig config, StreamHandler handler) {
        ObjectNode wire = transformer.toWire(request, request.model(), true);
        AnthropicStreamParser parser = new AnthropicStreamParser();
        try {
            HttpTransport.Response error = http.postSse(
                    endpoint(config),
                    headers(config),
                    wire.toString(),
                    config.timeout(),
                    name(),
                    request.model(),
                    payload -> {
                        ChatChunk chunk = parser.onEvent(readTree(payload, request.model()));
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

    private URI endpoint(ProviderConfig config) {
        String base = config.apiBase() != null ? config.apiBase() : DEFAULT_API_BASE;
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/v1/messages");
    }

    private Map<String, String> headers(ProviderConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (config.apiKey() != null) {
            headers.put("x-api-key", config.apiKey());
        }
        headers.put("anthropic-version", API_VERSION);
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
