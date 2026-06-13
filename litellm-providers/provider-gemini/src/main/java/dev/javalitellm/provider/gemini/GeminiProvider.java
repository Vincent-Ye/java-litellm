package dev.javalitellm.provider.gemini;

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

/** Google Gemini adapter (Google AI Studio API; Vertex AI lands later as a separate provider). */
public final class GeminiProvider implements LlmProvider {

    public static final String DEFAULT_API_BASE = "https://generativelanguage.googleapis.com/v1beta";

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiTransformer transformer = new GeminiTransformer(mapper);
    private final HttpTransport http = new HttpTransport();

    @Override
    public String name() {
        return "gemini";
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
        ObjectNode wire = transformer.toWire(request, request.model());
        HttpTransport.Response response = http.postJson(
                endpoint(config, request.model(), "generateContent", false),
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
        ObjectNode wire = transformer.toWire(request, request.model());
        try {
            HttpTransport.Response error = http.postSse(
                    endpoint(config, request.model(), "streamGenerateContent", true),
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
        var requests = wire.putArray("requests");
        for (String input : request.input()) {
            ObjectNode item = requests.addObject();
            item.put("model", "models/" + request.model());
            item.putObject("content").putArray("parts").addObject().put("text", input);
            if (request.dimensions() != null) {
                item.put("outputDimensionality", request.dimensions());
            }
        }

        HttpTransport.Response response = http.postJson(
                endpoint(config, request.model(), "batchEmbedContents", false),
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
        for (JsonNode item : root.path("embeddings")) {
            JsonNode values = item.path("values");
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            embeddings.add(vector);
        }
        // batchEmbedContents reports no usage
        return new EmbeddingResponse(request.model(), embeddings, (Usage) null, null);
    }

    private URI endpoint(ProviderConfig config, String model, String operation, boolean sse) {
        String base = config.apiBase() != null ? config.apiBase() : DEFAULT_API_BASE;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/models/" + model + ":" + operation + (sse ? "?alt=sse" : ""));
    }

    private Map<String, String> headers(ProviderConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (config.apiKey() != null) {
            headers.put("x-goog-api-key", config.apiKey());
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
