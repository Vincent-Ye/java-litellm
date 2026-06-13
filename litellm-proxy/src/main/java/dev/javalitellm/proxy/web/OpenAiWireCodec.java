package dev.javalitellm.proxy.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Choice;
import dev.javalitellm.core.chat.Content;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.chat.ResponseFormat;
import dev.javalitellm.core.chat.Role;
import dev.javalitellm.core.chat.Tool;
import dev.javalitellm.core.chat.ToolCall;
import dev.javalitellm.core.chat.ToolChoice;
import dev.javalitellm.core.chat.Usage;
import dev.javalitellm.core.exception.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Inbound codec for the proxy: OpenAI wire JSON ⇄ canonical types. The mirror image of
 * provider-openai's transformer (which speaks wire format outbound to upstreams).
 */
@Component
public class OpenAiWireCodec {

    private static final Set<String> KNOWN_FIELDS = Set.of(
            "model",
            "messages",
            "temperature",
            "top_p",
            "max_tokens",
            "max_completion_tokens",
            "stop",
            "tools",
            "tool_choice",
            "response_format",
            "user",
            "stream",
            "stream_options",
            "n");

    private final ObjectMapper mapper;

    public OpenAiWireCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ChatRequest parseChatRequest(JsonNode root) {
        ChatRequest.Builder builder = ChatRequest.builder();
        String model = root.path("model").asText(null);
        if (model == null) {
            throw new BadRequestException("'model' is required", null, null);
        }
        builder.model(model);
        for (JsonNode messageNode : root.path("messages")) {
            builder.message(parseMessage(messageNode));
        }
        if (root.hasNonNull("temperature")) {
            builder.temperature(root.get("temperature").asDouble());
        }
        if (root.hasNonNull("top_p")) {
            builder.topP(root.get("top_p").asDouble());
        }
        if (root.hasNonNull("max_tokens")) {
            builder.maxTokens(root.get("max_tokens").asInt());
        } else if (root.hasNonNull("max_completion_tokens")) {
            builder.maxTokens(root.get("max_completion_tokens").asInt());
        }
        if (root.hasNonNull("stop")) {
            List<String> stops = new ArrayList<>();
            if (root.get("stop").isTextual()) {
                stops.add(root.get("stop").asText());
            } else {
                root.get("stop").forEach(s -> stops.add(s.asText()));
            }
            builder.stop(stops);
        }
        if (root.hasNonNull("tools")) {
            List<Tool> tools = new ArrayList<>();
            for (JsonNode toolNode : root.get("tools")) {
                JsonNode fn = toolNode.path("function");
                tools.add(new Tool(
                        fn.path("name").asText(), fn.path("description").asText(null), fn.path("parameters")));
            }
            builder.tools(tools);
        }
        if (root.hasNonNull("tool_choice")) {
            builder.toolChoice(parseToolChoice(root.get("tool_choice")));
        }
        if (root.hasNonNull("response_format")) {
            builder.responseFormat(parseResponseFormat(root.get("response_format")));
        }
        if (root.hasNonNull("user")) {
            builder.user(root.get("user").asText());
        }
        root.properties().forEach(entry -> {
            if (!KNOWN_FIELDS.contains(entry.getKey())) {
                builder.extraParam(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class));
            }
        });
        return builder.build();
    }

    public boolean isStream(JsonNode root) {
        return root.path("stream").asBoolean(false);
    }

    private Message parseMessage(JsonNode node) {
        Role role =
                switch (node.path("role").asText()) {
                    case "system", "developer" -> Role.SYSTEM;
                    case "user" -> Role.USER;
                    case "assistant" -> Role.ASSISTANT;
                    case "tool" -> Role.TOOL;
                    default ->
                        throw new BadRequestException(
                                "unsupported message role '" + node.path("role").asText() + "'", null, null);
                };

        List<Content> content = new ArrayList<>();
        JsonNode contentNode = node.path("content");
        if (contentNode.isTextual()) {
            content.add(Content.text(contentNode.asText()));
        } else if (contentNode.isArray()) {
            for (JsonNode part : contentNode) {
                switch (part.path("type").asText()) {
                    case "text" -> content.add(Content.text(part.path("text").asText()));
                    case "image_url" ->
                        content.add(new Content.Image(
                                part.path("image_url").path("url").asText(),
                                part.path("image_url").path("detail").asText(null)));
                    case "input_audio" ->
                        content.add(new Content.Audio(
                                part.path("input_audio").path("data").asText(),
                                part.path("input_audio").path("format").asText()));
                    default ->
                        throw new BadRequestException(
                                "unsupported content part type '"
                                        + part.path("type").asText() + "'",
                                null,
                                null);
                }
            }
        }

        List<ToolCall> toolCalls = null;
        if (node.has("tool_calls")) {
            toolCalls = new ArrayList<>();
            for (JsonNode call : node.get("tool_calls")) {
                toolCalls.add(new ToolCall(
                        call.path("id").asText(),
                        call.path("function").path("name").asText(),
                        call.path("function").path("arguments").asText()));
            }
        }
        return new Message(role, content, toolCalls, node.path("tool_call_id").asText(null));
    }

    private ToolChoice parseToolChoice(JsonNode node) {
        if (node.isTextual()) {
            return switch (node.asText()) {
                case "auto" -> ToolChoice.AUTO;
                case "none" -> ToolChoice.NONE;
                case "required" -> ToolChoice.REQUIRED;
                default -> throw new BadRequestException("unsupported tool_choice '" + node.asText() + "'", null, null);
            };
        }
        return ToolChoice.function(node.path("function").path("name").asText());
    }

    private ResponseFormat parseResponseFormat(JsonNode node) {
        return switch (node.path("type").asText()) {
            case "json_object" -> ResponseFormat.JSON_OBJECT;
            case "json_schema" ->
                ResponseFormat.jsonSchema(
                        node.path("json_schema").path("name").asText(),
                        node.path("json_schema").path("schema"));
            default -> ResponseFormat.TEXT;
        };
    }

    public ObjectNode toWireResponse(ChatResponse response) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", response.id());
        root.put("object", "chat.completion");
        root.put("created", response.created() > 0 ? response.created() : System.currentTimeMillis() / 1000);
        root.put("model", response.model());
        ArrayNode choices = root.putArray("choices");
        for (Choice choice : response.choices()) {
            ObjectNode choiceNode = choices.addObject();
            choiceNode.put("index", choice.index());
            choiceNode.set("message", toWireMessage(choice.message()));
            choiceNode.put("finish_reason", choice.finishReason());
        }
        if (response.usage() != null) {
            root.set("usage", toWireUsage(response.usage()));
        }
        return root;
    }

    public ObjectNode toWireChunk(ChatChunk chunk) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", chunk.id());
        root.put("object", "chat.completion.chunk");
        root.put("created", System.currentTimeMillis() / 1000);
        root.put("model", chunk.model());
        ArrayNode choices = root.putArray("choices");
        if (!chunk.textDelta().isEmpty() || !chunk.toolCallDeltas().isEmpty() || chunk.finishReason() != null) {
            ObjectNode choiceNode = choices.addObject();
            choiceNode.put("index", 0);
            ObjectNode delta = choiceNode.putObject("delta");
            if (!chunk.textDelta().isEmpty()) {
                delta.put("content", chunk.textDelta());
            }
            if (!chunk.toolCallDeltas().isEmpty()) {
                ArrayNode calls = delta.putArray("tool_calls");
                for (ChatChunk.ToolCallDelta toolDelta : chunk.toolCallDeltas()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("index", toolDelta.index());
                    if (toolDelta.id() != null) {
                        callNode.put("id", toolDelta.id());
                    }
                    ObjectNode fn = callNode.putObject("function");
                    if (toolDelta.name() != null) {
                        fn.put("name", toolDelta.name());
                    }
                    if (toolDelta.argumentsDelta() != null) {
                        fn.put("arguments", toolDelta.argumentsDelta());
                    }
                }
            }
            choiceNode.put("finish_reason", chunk.finishReason());
        }
        if (chunk.usage() != null) {
            root.set("usage", toWireUsage(chunk.usage()));
        }
        return root;
    }

    private ObjectNode toWireMessage(Message message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", "assistant");
        node.put("content", message.text().isEmpty() && message.toolCalls() != null ? null : message.text());
        if (message.toolCalls() != null) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode callNode = calls.addObject();
                callNode.put("id", call.id());
                callNode.put("type", "function");
                ObjectNode fn = callNode.putObject("function");
                fn.put("name", call.name());
                fn.put("arguments", call.arguments());
            }
        }
        return node;
    }

    private ObjectNode toWireUsage(Usage usage) {
        ObjectNode node = mapper.createObjectNode();
        node.put("prompt_tokens", usage.promptTokens());
        node.put("completion_tokens", usage.completionTokens());
        node.put("total_tokens", usage.totalTokens());
        if (usage.cachedTokens() != null) {
            ObjectNode prompt = node.putObject("prompt_tokens_details");
            prompt.put("cached_tokens", usage.cachedTokens());
        }
        if (usage.reasoningTokens() != null) {
            ObjectNode completion = node.putObject("completion_tokens_details");
            completion.put("reasoning_tokens", usage.reasoningTokens());
        }
        return node;
    }
}
