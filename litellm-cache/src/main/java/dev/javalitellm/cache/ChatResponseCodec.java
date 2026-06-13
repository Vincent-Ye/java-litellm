package dev.javalitellm.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Choice;
import dev.javalitellm.core.chat.Content;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.chat.Role;
import dev.javalitellm.core.chat.ToolCall;
import dev.javalitellm.core.chat.Usage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON codec for {@link ChatResponse}. Hand-written because {@code Content} is a sealed interface
 * (default Jackson polymorphism would need extra annotations on core types we want to keep
 * wire-agnostic). Used by {@link RedisLlmCache}; in-process caches store object references directly.
 */
public final class ChatResponseCodec {

    private final ObjectMapper mapper;

    public ChatResponseCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(ChatResponse response) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", response.id());
        root.put("model", response.model());
        root.put("created", response.created());
        if (response.costUsd() != null) {
            root.put("cost_usd", response.costUsd().toPlainString());
        }
        if (response.usage() != null) {
            ObjectNode usage = root.putObject("usage");
            usage.put("prompt", response.usage().promptTokens());
            usage.put("completion", response.usage().completionTokens());
            if (response.usage().cachedTokens() != null) {
                usage.put("cached", response.usage().cachedTokens());
            }
            if (response.usage().reasoningTokens() != null) {
                usage.put("reasoning", response.usage().reasoningTokens());
            }
        }
        ArrayNode choices = root.putArray("choices");
        for (Choice choice : response.choices()) {
            ObjectNode choiceNode = choices.addObject();
            choiceNode.put("index", choice.index());
            if (choice.finishReason() != null) {
                choiceNode.put("finish_reason", choice.finishReason());
            }
            choiceNode.set("message", encodeMessage(choice.message()));
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ObjectNode encodeMessage(Message message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", message.role().name());
        if (message.toolCallId() != null) {
            node.put("tool_call_id", message.toolCallId());
        }
        ArrayNode content = node.putArray("content");
        for (Content part : message.content()) {
            ObjectNode partNode = content.addObject();
            switch (part) {
                case Content.Text(String text) -> {
                    partNode.put("kind", "text");
                    partNode.put("text", text);
                }
                case Content.Image(String url, String detail) -> {
                    partNode.put("kind", "image");
                    partNode.put("url", url);
                    if (detail != null) {
                        partNode.put("detail", detail);
                    }
                }
                case Content.Audio(String data, String format) -> {
                    partNode.put("kind", "audio");
                    partNode.put("data", data);
                    partNode.put("format", format);
                }
            }
        }
        if (message.toolCalls() != null) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode callNode = calls.addObject();
                callNode.put("id", call.id());
                callNode.put("name", call.name());
                callNode.put("arguments", call.arguments());
            }
        }
        return node;
    }

    public ChatResponse decode(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Usage usage = root.has("usage")
                ? new Usage(
                        root.get("usage").path("prompt").asInt(),
                        root.get("usage").path("completion").asInt(),
                        root.get("usage").hasNonNull("cached")
                                ? root.get("usage").get("cached").asInt()
                                : null,
                        root.get("usage").hasNonNull("reasoning")
                                ? root.get("usage").get("reasoning").asInt()
                                : null)
                : null;
        BigDecimal cost = root.hasNonNull("cost_usd")
                ? new BigDecimal(root.get("cost_usd").asText())
                : null;

        List<Choice> choices = new ArrayList<>();
        for (JsonNode choiceNode : root.path("choices")) {
            choices.add(new Choice(
                    choiceNode.path("index").asInt(),
                    decodeMessage(choiceNode.path("message")),
                    choiceNode.path("finish_reason").asText(null)));
        }
        return new ChatResponse(
                root.path("id").asText(null),
                root.path("model").asText(null),
                root.path("created").asLong(),
                choices,
                usage,
                cost);
    }

    private Message decodeMessage(JsonNode node) {
        Role role = Role.valueOf(node.path("role").asText());
        List<Content> content = new ArrayList<>();
        for (JsonNode partNode : node.path("content")) {
            switch (partNode.path("kind").asText()) {
                case "text" -> content.add(Content.text(partNode.path("text").asText()));
                case "image" ->
                    content.add(new Content.Image(
                            partNode.path("url").asText(),
                            partNode.hasNonNull("detail")
                                    ? partNode.get("detail").asText()
                                    : null));
                case "audio" ->
                    content.add(new Content.Audio(
                            partNode.path("data").asText(),
                            partNode.path("format").asText()));
                default -> {
                    // unknown content kinds are dropped on decode rather than failing
                }
            }
        }
        List<ToolCall> toolCalls = null;
        if (node.has("tool_calls")) {
            toolCalls = new ArrayList<>();
            for (JsonNode callNode : node.get("tool_calls")) {
                toolCalls.add(new ToolCall(
                        callNode.path("id").asText(),
                        callNode.path("name").asText(),
                        callNode.path("arguments").asText()));
            }
        }
        return new Message(role, content, toolCalls, node.path("tool_call_id").asText(null));
    }
}
