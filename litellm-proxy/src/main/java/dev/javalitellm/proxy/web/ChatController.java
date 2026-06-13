package dev.javalitellm.proxy.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.cache.ChatCacheKey;
import dev.javalitellm.client.LiteLlm;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.embedding.EmbeddingRequest;
import dev.javalitellm.core.embedding.EmbeddingResponse;
import dev.javalitellm.core.exception.LiteLlmException;
import dev.javalitellm.core.exception.NotFoundException;
import dev.javalitellm.core.exception.PermissionDeniedException;
import dev.javalitellm.core.exception.RateLimitException;
import dev.javalitellm.core.spi.StreamHandler;
import dev.javalitellm.proxy.auth.AuthFilter;
import dev.javalitellm.proxy.cache.ProxyCache;
import dev.javalitellm.proxy.keys.VirtualKey;
import dev.javalitellm.proxy.metrics.ProxyMetrics;
import dev.javalitellm.proxy.ratelimit.RateLimiter;
import dev.javalitellm.proxy.spend.SpendService;
import dev.javalitellm.proxy.teams.Team;
import dev.javalitellm.proxy.teams.TeamService;
import dev.javalitellm.router.Deployment;
import dev.javalitellm.router.Router;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final Router router;
    private final LiteLlm client;
    private final OpenAiWireCodec codec;
    private final SpendService spend;
    private final ObjectMapper mapper;
    private final ProxyCache cache;
    private final RateLimiter rateLimiter;
    private final ProxyMetrics metrics;
    private final TeamService teams;

    public ChatController(
            Router router,
            LiteLlm client,
            OpenAiWireCodec codec,
            SpendService spend,
            ObjectMapper mapper,
            ProxyCache cache,
            RateLimiter rateLimiter,
            ProxyMetrics metrics,
            TeamService teams) {
        this.router = router;
        this.client = client;
        this.codec = codec;
        this.spend = spend;
        this.mapper = mapper;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.teams = teams;
    }

    @PostMapping(value = "/v1/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public void chatCompletions(@RequestBody JsonNode body, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ChatRequest chatRequest = codec.parseChatRequest(body);
        String group = chatRequest.model();
        VirtualKey key = authorize(request, group);
        enforceLimits(key, group);

        if (!codec.isStream(body)) {
            chatJson(chatRequest, group, key, response);
            return;
        }

        long startNanos = System.nanoTime();
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        router.chatStream(chatRequest, new StreamHandler() {
            @Override
            public void onChunk(ChatChunk chunk) {
                if (chunk.usage() != null) {
                    recordTokenUsage(key, chunk.usage().totalTokens());
                }
                writeEvent(writer, codec.toWireChunk(chunk).toString());
            }

            @Override
            public void onComplete() {
                metrics.recordRequest(group, "ok", sinceNanos(startNanos));
                writeEvent(writer, "[DONE]");
            }

            @Override
            public void onError(LiteLlmException e) {
                // mid-stream failure: emit an error event, the connection is already committed
                metrics.recordRequest(group, "error", sinceNanos(startNanos));
                writeEvent(writer, "{\"error\":{\"message\":\"" + e.getMessage() + "\"}}");
            }
        });
    }

    private void chatJson(ChatRequest chatRequest, String group, VirtualKey key, HttpServletResponse response)
            throws IOException {
        String cacheKey = cache.enabled() ? ChatCacheKey.of(chatRequest) : null;
        if (cacheKey != null) {
            Optional<ChatResponse> hit = cache.get(cacheKey);
            metrics.recordCache(group, hit.isPresent());
            if (hit.isPresent()) {
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader("x-litellm-cache", "hit");
                mapper.writeValue(response.getWriter(), codec.toWireResponse(hit.get()));
                return;
            }
        }

        long startNanos = System.nanoTime();
        ChatResponse chatResponse;
        try {
            chatResponse = router.chat(chatRequest);
        } catch (LiteLlmException e) {
            metrics.recordRequest(group, "error", sinceNanos(startNanos));
            throw e;
        }
        metrics.recordRequest(group, "ok", sinceNanos(startNanos));
        metrics.recordUsage(group, chatResponse);
        if (chatResponse.usage() != null) {
            recordTokenUsage(key, chatResponse.usage().totalTokens());
        }
        recordSpend(key, group, chatResponse);
        if (cacheKey != null) {
            cache.put(cacheKey, chatResponse);
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), codec.toWireResponse(chatResponse));
    }

    /** Enforces team budget/blocking and the key→team rate-limit cascade. Master key bypasses all. */
    private void enforceLimits(VirtualKey key, String group) {
        if (key == null) {
            return;
        }
        Team team = key.teamId() == null ? null : teams.find(key.teamId()).orElse(null);
        if (team != null) {
            if (team.blocked()) {
                throw new PermissionDeniedException("team '" + team.teamId() + "' is blocked", null, group);
            }
            if (team.overBudget()) {
                throw new RateLimitException("team '" + team.teamId() + "' has exceeded its budget", null, group);
            }
        }

        if (!rateLimiter.withinTokenLimit(key.tokenHash(), key.tpmLimit())) {
            metrics.recordRateLimited(group, "tpm");
            throw new RateLimitException("key exceeded its TPM limit of " + key.tpmLimit(), null, group);
        }
        if (team != null && !rateLimiter.withinTokenLimit("team:" + team.teamId(), team.tpmLimit())) {
            metrics.recordRateLimited(group, "team_tpm");
            throw new RateLimitException("team exceeded its TPM limit of " + team.tpmLimit(), null, group);
        }
        if (!rateLimiter.tryAcquireRequest(key.tokenHash(), key.rpmLimit())) {
            metrics.recordRateLimited(group, "rpm");
            throw new RateLimitException("key exceeded its RPM limit of " + key.rpmLimit(), null, group);
        }
        if (team != null && !rateLimiter.tryAcquireRequest("team:" + team.teamId(), team.rpmLimit())) {
            metrics.recordRateLimited(group, "team_rpm");
            throw new RateLimitException("team exceeded its RPM limit of " + team.rpmLimit(), null, group);
        }
    }

    private void recordTokenUsage(VirtualKey key, int totalTokens) {
        if (key != null) {
            rateLimiter.recordTokens(key.tokenHash(), totalTokens);
            if (key.teamId() != null) {
                rateLimiter.recordTokens("team:" + key.teamId(), totalTokens);
            }
        }
    }

    private static Duration sinceNanos(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    @PostMapping(value = "/v1/embeddings", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode embeddings(@RequestBody JsonNode body, HttpServletRequest request) {
        String group = body.path("model").asText();
        VirtualKey key = authorize(request, group);

        List<String> input = new ArrayList<>();
        if (body.path("input").isTextual()) {
            input.add(body.path("input").asText());
        } else {
            body.path("input").forEach(node -> input.add(node.asText()));
        }
        Deployment deployment = router.pick(group);
        if (deployment == null) {
            throw new NotFoundException("unknown model group '" + group + "'", null, group);
        }
        EmbeddingRequest embeddingRequest = new EmbeddingRequest(
                deployment.model(),
                input,
                body.hasNonNull("dimensions") ? body.get("dimensions").asInt() : null,
                null);
        EmbeddingResponse result = client.embedding(embeddingRequest, deployment.config());

        ObjectNode root = mapper.createObjectNode();
        root.put("object", "list");
        root.put("model", result.model());
        ArrayNode data = root.putArray("data");
        for (int i = 0; i < result.embeddings().size(); i++) {
            ObjectNode item = data.addObject();
            item.put("object", "embedding");
            item.put("index", i);
            ArrayNode vector = item.putArray("embedding");
            for (float v : result.embeddings().get(i)) {
                vector.add(v);
            }
        }
        if (result.usage() != null) {
            ObjectNode usage = root.putObject("usage");
            usage.put("prompt_tokens", result.usage().promptTokens());
            usage.put("total_tokens", result.usage().totalTokens());
        }
        return root;
    }

    @GetMapping(value = "/v1/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode models(HttpServletRequest request) {
        VirtualKey key = (VirtualKey) request.getAttribute(AuthFilter.ATTR_VIRTUAL_KEY);
        ObjectNode root = mapper.createObjectNode();
        root.put("object", "list");
        ArrayNode data = root.putArray("data");
        router.modelGroups().stream()
                .filter(group -> key == null || key.allowsModel(group))
                .forEach(group -> {
                    ObjectNode model = data.addObject();
                    model.put("id", group);
                    model.put("object", "model");
                    model.put("owned_by", "litellm");
                });
        return root;
    }

    private VirtualKey authorize(HttpServletRequest request, String modelGroup) {
        VirtualKey key = (VirtualKey) request.getAttribute(AuthFilter.ATTR_VIRTUAL_KEY);
        if (key != null && !key.allowsModel(modelGroup)) {
            throw new PermissionDeniedException(
                    "key is not allowed to call model '" + modelGroup + "'", null, modelGroup);
        }
        return key;
    }

    private void recordSpend(VirtualKey key, String group, ChatResponse response) {
        if (key != null) {
            spend.record(key, group, response);
        }
    }

    private static void writeEvent(PrintWriter writer, String data) {
        writer.write("data: " + data + "\n\n");
        writer.flush();
        if (writer.checkError()) {
            throw new UncheckedIOException(new IOException("client disconnected"));
        }
    }
}
