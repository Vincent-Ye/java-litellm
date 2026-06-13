package dev.javalitellm.provider.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import dev.javalitellm.core.chat.Role;
import dev.javalitellm.core.chat.Tool;
import dev.javalitellm.core.chat.ToolCall;
import dev.javalitellm.core.chat.Usage;
import dev.javalitellm.core.exception.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mapping between canonical (OpenAI-format) types and Gemini's generateContent API.
 *
 * <p>Differences handled here: roles are user/model, system prompts become {@code systemInstruction},
 * tools nest under {@code functionDeclarations}, tool calls/results are {@code functionCall} /
 * {@code functionResponse} parts, and sampling knobs live in {@code generationConfig}. Gemini
 * function calls carry no id, so the function name doubles as the canonical {@code ToolCall.id}.
 */
final class GeminiTransformer {

    private static final String PROVIDER = "gemini";

    private final ObjectMapper mapper;

    GeminiTransformer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode toWire(ChatRequest req, String model) {
        ObjectNode root = mapper.createObjectNode();

        StringBuilder system = new StringBuilder();
        ArrayNode contents = root.putArray("contents");
        for (Message msg : req.messages()) {
            if (msg.role() == Role.SYSTEM) {
                if (!system.isEmpty()) {
                    system.append('\n');
                }
                system.append(msg.text());
            } else {
                contents.add(toWireContent(msg, model));
            }
        }
        if (!system.isEmpty()) {
            root.putObject("systemInstruction").putArray("parts").addObject().put("text", system.toString());
        }

        if (req.tools() != null) {
            ArrayNode declarations = root.putArray("tools").addObject().putArray("functionDeclarations");
            for (Tool tool : req.tools()) {
                ObjectNode declaration = declarations.addObject();
                declaration.put("name", tool.name());
                if (tool.description() != null) {
                    declaration.put("description", tool.description());
                }
                declaration.set("parameters", tool.parameters());
            }
        }
        if (req.toolChoice() != null) {
            ObjectNode fnConfig = root.putObject("toolConfig").putObject("functionCallingConfig");
            switch (req.toolChoice().mode()) {
                case AUTO -> fnConfig.put("mode", "AUTO");
                case NONE -> fnConfig.put("mode", "NONE");
                case REQUIRED -> fnConfig.put("mode", "ANY");
                case FUNCTION -> {
                    fnConfig.put("mode", "ANY");
                    fnConfig.putArray("allowedFunctionNames")
                            .add(req.toolChoice().functionName());
                }
            }
        }

        ObjectNode generation = mapper.createObjectNode();
        if (req.temperature() != null) {
            generation.put("temperature", req.temperature());
        }
        if (req.topP() != null) {
            generation.put("topP", req.topP());
        }
        if (req.maxTokens() != null) {
            generation.put("maxOutputTokens", req.maxTokens());
        }
        if (req.stop() != null) {
            ArrayNode stops = generation.putArray("stopSequences");
            req.stop().forEach(stops::add);
        }
        if (req.responseFormat() != null) {
            switch (req.responseFormat().type()) {
                case JSON_OBJECT -> generation.put("responseMimeType", "application/json");
                case JSON_SCHEMA -> {
                    generation.put("responseMimeType", "application/json");
                    generation.set("responseSchema", req.responseFormat().jsonSchema());
                }
                case TEXT -> {
                    // default
                }
            }
        }
        if (!generation.isEmpty()) {
            root.set("generationConfig", generation);
        }
        for (Map.Entry<String, Object> extra : req.extraParams().entrySet()) {
            root.set(extra.getKey(), mapper.valueToTree(extra.getValue()));
        }
        return root;
    }

    private ObjectNode toWireContent(Message msg, String model) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", msg.role() == Role.ASSISTANT ? "model" : "user");
        ArrayNode parts = node.putArray("parts");

        if (msg.role() == Role.TOOL) {
            ObjectNode response = parts.addObject().putObject("functionResponse");
            response.put("name", msg.toolCallId()); // Gemini has no call ids; name doubles as id
            response.putObject("response").put("content", msg.text());
            return node;
        }

        for (Content part : msg.content()) {
            switch (part) {
                case Content.Text(String text) -> parts.addObject().put("text", text);
                case Content.Image(String url, String ignored) -> {
                    if (!url.startsWith("data:")) {
                        ObjectNode fileData = parts.addObject().putObject("fileData");
                        fileData.put("fileUri", url);
                    } else {
                        int semi = url.indexOf(';');
                        int comma = url.indexOf(',');
                        if (semi < 0 || comma < 0) {
                            throw new BadRequestException("malformed data URI in image content", PROVIDER, model);
                        }
                        ObjectNode inline = parts.addObject().putObject("inlineData");
                        inline.put("mimeType", url.substring("data:".length(), semi));
                        inline.put("data", url.substring(comma + 1));
                    }
                }
                case Content.Audio(String data, String format) -> {
                    ObjectNode inline = parts.addObject().putObject("inlineData");
                    inline.put("mimeType", "audio/" + format);
                    inline.put("data", data);
                }
            }
        }
        if (msg.toolCalls() != null) {
            for (ToolCall call : msg.toolCalls()) {
                ObjectNode fnCall = parts.addObject().putObject("functionCall");
                fnCall.put("name", call.name());
                fnCall.set("args", parseJson(call.arguments(), model));
            }
        }
        return node;
    }

    ChatResponse fromWire(JsonNode root) {
        JsonNode candidate = root.path("candidates").path(0);

        List<Content> content = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.has("text")) {
                content.add(Content.text(part.path("text").asText()));
            } else if (part.has("functionCall")) {
                JsonNode call = part.path("functionCall");
                String name = call.path("name").asText();
                toolCalls.add(new ToolCall(name, name, call.path("args").toString()));
            }
        }
        Message message = new Message(Role.ASSISTANT, content, toolCalls.isEmpty() ? null : toolCalls, null);

        return new ChatResponse(
                root.path("responseId").asText(null),
                root.path("modelVersion").asText(null),
                0,
                List.of(new Choice(
                        0,
                        message,
                        mapFinishReason(candidate.path("finishReason").asText(null), !toolCalls.isEmpty()))),
                fromWireUsage(root.path("usageMetadata")),
                null);
    }

    /** Maps one SSE payload to a chunk; Gemini streams whole parts, so tool-call args arrive complete. */
    ChatChunk chunkFromWire(JsonNode root) {
        JsonNode candidate = root.path("candidates").path(0);

        StringBuilder text = new StringBuilder();
        List<ChatChunk.ToolCallDelta> toolDeltas = new ArrayList<>();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.has("text")) {
                text.append(part.path("text").asText());
            } else if (part.has("functionCall")) {
                JsonNode call = part.path("functionCall");
                String name = call.path("name").asText();
                toolDeltas.add(new ChatChunk.ToolCallDelta(
                        toolDeltas.size(), name, name, call.path("args").toString()));
            }
        }

        Usage usage = fromWireUsage(root.path("usageMetadata"));
        String finishReason = mapFinishReason(candidate.path("finishReason").asText(null), !toolDeltas.isEmpty());
        if (text.isEmpty() && toolDeltas.isEmpty() && finishReason == null && usage == null) {
            return null;
        }
        return new ChatChunk(
                root.path("responseId").asText(null),
                root.path("modelVersion").asText(null),
                text.toString(),
                toolDeltas,
                finishReason,
                usage);
    }

    private Usage fromWireUsage(JsonNode node) {
        if (node.isMissingNode() || !node.has("promptTokenCount")) {
            return null;
        }
        return new Usage(
                node.path("promptTokenCount").asInt(),
                node.path("candidatesTokenCount").asInt(),
                node.has("cachedContentTokenCount")
                        ? node.path("cachedContentTokenCount").asInt()
                        : null,
                node.has("thoughtsTokenCount") ? node.path("thoughtsTokenCount").asInt() : null);
    }

    private static String mapFinishReason(String geminiReason, boolean hasToolCalls) {
        if (hasToolCalls) {
            return "tool_calls";
        }
        return switch (geminiReason) {
            case null -> null;
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> "content_filter";
            default -> geminiReason.toLowerCase();
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
