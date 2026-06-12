package dev.javalitellm.provider.openai;

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
import dev.javalitellm.core.chat.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure request/response mapping between canonical types and the OpenAI chat-completions wire format.
 * Canonical types already mirror OpenAI semantics, so this transformer is mostly mechanical — it is
 * still kept explicit (no direct Jackson serialization of core types) so core stays wire-agnostic.
 */
final class OpenAiTransformer {

    private final ObjectMapper mapper;

    OpenAiTransformer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode toWire(ChatRequest req, String bareModel, boolean stream) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", bareModel);

        ArrayNode messages = root.putArray("messages");
        for (Message msg : req.messages()) {
            messages.add(toWireMessage(msg));
        }

        if (req.temperature() != null) {
            root.put("temperature", req.temperature());
        }
        if (req.topP() != null) {
            root.put("top_p", req.topP());
        }
        if (req.maxTokens() != null) {
            root.put("max_tokens", req.maxTokens());
        }
        if (req.stop() != null) {
            ArrayNode stop = root.putArray("stop");
            req.stop().forEach(stop::add);
        }
        if (req.tools() != null) {
            ArrayNode tools = root.putArray("tools");
            for (Tool tool : req.tools()) {
                ObjectNode fn = mapper.createObjectNode();
                fn.put("name", tool.name());
                if (tool.description() != null) {
                    fn.put("description", tool.description());
                }
                fn.set("parameters", tool.parameters());
                ObjectNode wrapper = tools.addObject();
                wrapper.put("type", "function");
                wrapper.set("function", fn);
            }
        }
        if (req.toolChoice() != null) {
            switch (req.toolChoice().mode()) {
                case AUTO -> root.put("tool_choice", "auto");
                case NONE -> root.put("tool_choice", "none");
                case REQUIRED -> root.put("tool_choice", "required");
                case FUNCTION -> {
                    ObjectNode choice = root.putObject("tool_choice");
                    choice.put("type", "function");
                    choice.putObject("function").put("name", req.toolChoice().functionName());
                }
            }
        }
        if (req.responseFormat() != null) {
            root.set("response_format", toWireResponseFormat(req.responseFormat()));
        }
        if (req.user() != null) {
            root.put("user", req.user());
        }
        if (stream) {
            root.put("stream", true);
            root.putObject("stream_options").put("include_usage", true);
        }
        for (Map.Entry<String, Object> extra : req.extraParams().entrySet()) {
            root.set(extra.getKey(), mapper.valueToTree(extra.getValue()));
        }
        return root;
    }

    private ObjectNode toWireMessage(Message msg) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", msg.role().wireName());

        boolean textOnly = msg.content().stream().allMatch(part -> part instanceof Content.Text);
        if (textOnly) {
            node.put("content", msg.text());
        } else {
            ArrayNode parts = node.putArray("content");
            for (Content part : msg.content()) {
                parts.add(toWireContentPart(part));
            }
        }

        if (msg.toolCalls() != null) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall call : msg.toolCalls()) {
                ObjectNode callNode = calls.addObject();
                callNode.put("id", call.id());
                callNode.put("type", "function");
                ObjectNode fn = callNode.putObject("function");
                fn.put("name", call.name());
                fn.put("arguments", call.arguments());
            }
        }
        if (msg.role() == Role.TOOL && msg.toolCallId() != null) {
            node.put("tool_call_id", msg.toolCallId());
        }
        return node;
    }

    private ObjectNode toWireContentPart(Content part) {
        ObjectNode node = mapper.createObjectNode();
        switch (part) {
            case Content.Text(String text) -> {
                node.put("type", "text");
                node.put("text", text);
            }
            case Content.Image(String url, String detail) -> {
                node.put("type", "image_url");
                ObjectNode image = node.putObject("image_url");
                image.put("url", url);
                if (detail != null) {
                    image.put("detail", detail);
                }
            }
            case Content.Audio(String data, String format) -> {
                node.put("type", "input_audio");
                ObjectNode audio = node.putObject("input_audio");
                audio.put("data", data);
                audio.put("format", format);
            }
        }
        return node;
    }

    private ObjectNode toWireResponseFormat(ResponseFormat format) {
        ObjectNode node = mapper.createObjectNode();
        switch (format.type()) {
            case TEXT -> node.put("type", "text");
            case JSON_OBJECT -> node.put("type", "json_object");
            case JSON_SCHEMA -> {
                node.put("type", "json_schema");
                ObjectNode schema = node.putObject("json_schema");
                schema.put("name", format.schemaName());
                schema.set("schema", format.jsonSchema());
            }
        }
        return node;
    }

    ChatResponse fromWire(JsonNode root) {
        List<Choice> choices = new ArrayList<>();
        for (JsonNode choiceNode : root.path("choices")) {
            choices.add(new Choice(
                    choiceNode.path("index").asInt(),
                    fromWireMessage(choiceNode.path("message")),
                    choiceNode.path("finish_reason").asText(null)));
        }
        return new ChatResponse(
                root.path("id").asText(null),
                root.path("model").asText(null),
                root.path("created").asLong(0),
                choices,
                fromWireUsage(root.path("usage")),
                null);
    }

    private Message fromWireMessage(JsonNode node) {
        List<Content> content = new ArrayList<>();
        JsonNode contentNode = node.path("content");
        if (contentNode.isTextual()) {
            content.add(Content.text(contentNode.asText()));
        }

        List<ToolCall> toolCalls = null;
        if (node.has("tool_calls")) {
            toolCalls = new ArrayList<>();
            for (JsonNode callNode : node.get("tool_calls")) {
                toolCalls.add(new ToolCall(
                        callNode.path("id").asText(),
                        callNode.path("function").path("name").asText(),
                        callNode.path("function").path("arguments").asText()));
            }
        }
        return new Message(Role.ASSISTANT, content, toolCalls, null);
    }

    private Usage fromWireUsage(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        Integer cached = node.path("prompt_tokens_details").has("cached_tokens")
                ? node.path("prompt_tokens_details").path("cached_tokens").asInt()
                : null;
        Integer reasoning = node.path("completion_tokens_details").has("reasoning_tokens")
                ? node.path("completion_tokens_details")
                        .path("reasoning_tokens")
                        .asInt()
                : null;
        return new Usage(
                node.path("prompt_tokens").asInt(),
                node.path("completion_tokens").asInt(),
                cached,
                reasoning);
    }

    /** Maps one SSE data payload to a chunk; returns null for payloads without choices or usage. */
    ChatChunk chunkFromWire(JsonNode root) {
        JsonNode choices = root.path("choices");
        Usage usage = fromWireUsage(root.path("usage"));
        if (choices.isEmpty() && usage == null) {
            return null;
        }

        String textDelta = null;
        String finishReason = null;
        List<ChatChunk.ToolCallDelta> toolCallDeltas = new ArrayList<>();
        if (!choices.isEmpty()) {
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.path("delta");
            textDelta = delta.path("content").asText(null);
            finishReason = choice.path("finish_reason").asText(null);
            for (JsonNode callNode : delta.path("tool_calls")) {
                toolCallDeltas.add(new ChatChunk.ToolCallDelta(
                        callNode.path("index").asInt(),
                        callNode.path("id").asText(null),
                        callNode.path("function").path("name").asText(null),
                        callNode.path("function").path("arguments").asText(null)));
            }
        }
        return new ChatChunk(
                root.path("id").asText(null),
                root.path("model").asText(null),
                textDelta,
                toolCallDeltas,
                finishReason,
                usage);
    }
}
