package dev.javalitellm.proxy.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.client.LiteLlm;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.embedding.EmbeddingRequest;
import dev.javalitellm.core.embedding.EmbeddingResponse;
import dev.javalitellm.core.exception.LiteLlmException;
import dev.javalitellm.core.exception.NotFoundException;
import dev.javalitellm.core.exception.PermissionDeniedException;
import dev.javalitellm.core.spi.StreamHandler;
import dev.javalitellm.proxy.auth.AuthFilter;
import dev.javalitellm.proxy.keys.VirtualKey;
import dev.javalitellm.proxy.spend.SpendService;
import dev.javalitellm.router.Deployment;
import dev.javalitellm.router.Router;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
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

    public ChatController(
            Router router, LiteLlm client, OpenAiWireCodec codec, SpendService spend, ObjectMapper mapper) {
        this.router = router;
        this.client = client;
        this.codec = codec;
        this.spend = spend;
        this.mapper = mapper;
    }

    @PostMapping(value = "/v1/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public void chatCompletions(@RequestBody JsonNode body, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ChatRequest chatRequest = codec.parseChatRequest(body);
        String group = chatRequest.model();
        VirtualKey key = authorize(request, group);

        if (!codec.isStream(body)) {
            ChatResponse chatResponse = router.chat(chatRequest);
            recordSpend(key, group, chatResponse);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getWriter(), codec.toWireResponse(chatResponse));
            return;
        }

        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        router.chatStream(chatRequest, new StreamHandler() {
            @Override
            public void onChunk(ChatChunk chunk) {
                writeEvent(writer, codec.toWireChunk(chunk).toString());
            }

            @Override
            public void onComplete() {
                writeEvent(writer, "[DONE]");
            }

            @Override
            public void onError(LiteLlmException e) {
                // mid-stream failure: emit an error event, the connection is already committed
                writeEvent(writer, "{\"error\":{\"message\":\"" + e.getMessage() + "\"}}");
            }
        });
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
            spend.record(key.tokenHash(), group, response);
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
