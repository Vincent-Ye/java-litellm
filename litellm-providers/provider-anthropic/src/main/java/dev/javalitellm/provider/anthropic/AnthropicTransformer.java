package dev.javalitellm.provider.anthropic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Choice;
import dev.javalitellm.core.chat.Content;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.chat.Role;
import dev.javalitellm.core.chat.Tool;
import dev.javalitellm.core.chat.ToolCall;
import dev.javalitellm.core.chat.Usage;
import dev.javalitellm.core.exception.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mapping between canonical (OpenAI-format) types and the Anthropic Messages API.
 *
 * <p>Notable differences handled here: system prompts are a top-level parameter, {@code max_tokens}
 * is required (defaulted when absent), assistant tool calls become {@code tool_use} blocks, tool
 * results become {@code tool_result} blocks inside a user message, and stop reasons use different
 * names.
 */
final class AnthropicTransformer {

    static final int DEFAULT_MAX_TOKENS = 4096;
    private static final String PROVIDER = "anthropic";

    private final ObjectMapper mapper;

    AnthropicTransformer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode toWire(ChatRequest req, String bareModel, boolean stream) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", bareModel);
        root.put("max_tokens", req.maxTokens() != null ? req.maxTokens() : DEFAULT_MAX_TOKENS);

        StringBuilder system = new StringBuilder();
        ArrayNode messages = root.putArray("messages");
        for (Message msg : req.messages()) {
            if (msg.role() == Role.SYSTEM) {
                if (!system.isEmpty()) {
                    system.append('\n');
                }
                system.append(msg.text());
            } else {
                messages.add(toWireMessage(msg, bareModel));
            }
        }
        if (!system.isEmpty()) {
            root.put("system", system.toString());
        }

        if (req.temperature() != null) {
            root.put("temperature", req.temperature());
        }
        if (req.topP() != null) {
            root.put("top_p", req.topP());
        }
        if (req.stop() != null) {
            ArrayNode stop = root.putArray("stop_sequences");
            req.stop().forEach(stop::add);
        }
        if (req.tools() != null) {
            ArrayNode tools = root.putArray("tools");
            for (Tool tool : req.tools()) {
                ObjectNode toolNode = tools.addObject();
                toolNode.put("name", tool.name());
                if (tool.description() != null) {
                    toolNode.put("description", tool.description());
                }
                toolNode.set("input_schema", tool.parameters());
            }
        }
        if (req.toolChoice() != null) {
            ObjectNode choice = root.putObject("tool_choice");
            switch (req.toolChoice().mode()) {
                case AUTO -> choice.put("type", "auto");
                case REQUIRED -> choice.put("type", "any");
                case NONE -> choice.put("type", "none");
                case FUNCTION -> {
                    choice.put("type", "tool");
                    choice.put("name", req.toolChoice().functionName());
                }
            }
        }
        if (stream) {
            root.put("stream", true);
        }
        for (Map.Entry<String, Object> extra : req.extraParams().entrySet()) {
            root.set(extra.getKey(), mapper.valueToTree(extra.getValue()));
        }
        return root;
    }

    private ObjectNode toWireMessage(Message msg, String model) {
        ObjectNode node = mapper.createObjectNode();

        if (msg.role() == Role.TOOL) {
            // Tool results travel as a tool_result block inside a user message.
            node.put("role", "user");
            ObjectNode block = node.putArray("content").addObject();
            block.put("type", "tool_result");
            block.put("tool_use_id", msg.toolCallId());
            block.put("content", msg.text());
            return node;
        }

        node.put("role", msg.role() == Role.ASSISTANT ? "assistant" : "user");
        ArrayNode blocks = node.putArray("content");
        for (Content part : msg.content()) {
            blocks.add(toWireContentBlock(part, model));
        }
        if (msg.toolCalls() != null) {
            for (ToolCall call : msg.toolCalls()) {
                ObjectNode block = blocks.addObject();
                block.put("type", "tool_use");
                block.put("id", call.id());
                block.put("name", call.name());
                block.set("input", parseJson(call.arguments(), model));
            }
        }
        return node;
    }

    private ObjectNode toWireContentBlock(Content part, String model) {
        ObjectNode block = mapper.createObjectNode();
        switch (part) {
            case Content.Text(String text) -> {
                block.put("type", "text");
                block.put("text", text);
            }
            case Content.Image(String url, String ignored) -> {
                block.put("type", "image");
                ObjectNode source = block.putObject("source");
                if (url.startsWith("data:")) {
                    // data:<media_type>;base64,<data>
                    int semi = url.indexOf(';');
                    int comma = url.indexOf(',');
                    if (semi < 0 || comma < 0) {
                        throw new BadRequestException("malformed data URI in image content", PROVIDER, model);
                    }
                    source.put("type", "base64");
                    source.put("media_type", url.substring("data:".length(), semi));
                    source.put("data", url.substring(comma + 1));
                } else {
                    source.put("type", "url");
                    source.put("url", url);
                }
            }
            case Content.Audio ignored ->
                throw new BadRequestException("anthropic does not accept audio content", PROVIDER, model);
        }
        return block;
    }

    ChatResponse fromWire(JsonNode root) {
        List<Content> content = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode block : root.path("content")) {
            switch (block.path("type").asText()) {
                case "text" -> content.add(Content.text(block.path("text").asText()));
                case "tool_use" ->
                    toolCalls.add(new ToolCall(
                            block.path("id").asText(),
                            block.path("name").asText(),
                            block.path("input").toString()));
                default -> {
                    // thinking/citations and future block types: not part of the canonical surface yet
                }
            }
        }
        Message message = new Message(Role.ASSISTANT, content, toolCalls.isEmpty() ? null : toolCalls, null);

        JsonNode usageNode = root.path("usage");
        Usage usage = usageNode.isMissingNode()
                ? null
                : new Usage(
                        usageNode.path("input_tokens").asInt(),
                        usageNode.path("output_tokens").asInt(),
                        usageNode.has("cache_read_input_tokens")
                                ? usageNode.path("cache_read_input_tokens").asInt()
                                : null,
                        null);

        return new ChatResponse(
                root.path("id").asText(null),
                root.path("model").asText(null),
                0,
                List.of(new Choice(
                        0, message, mapStopReason(root.path("stop_reason").asText(null)))),
                usage,
                null);
    }

    static String mapStopReason(String anthropicStopReason) {
        return switch (anthropicStopReason) {
            case null -> null;
            case "end_turn", "stop_sequence" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            default -> anthropicStopReason;
        };
    }

    private JsonNode parseJson(String json, String model) {
        try {
            return mapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("tool call arguments are not valid JSON: " + json, PROVIDER, model, e);
        }
    }
}
