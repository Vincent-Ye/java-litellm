package dev.javalitellm.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.Usage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateful parser for one Anthropic SSE stream, mapping its event types (message_start,
 * content_block_start/delta, message_delta, ...) to canonical chunks. Tool-call deltas keep the
 * content-block index so fragments can be stitched per call; input token counts arrive in
 * message_start and are re-attached to the final usage chunk.
 */
final class AnthropicStreamParser {

    private String messageId;
    private String model;
    private int inputTokens;
    private Integer cachedTokens;
    private final Map<Integer, String> toolNamesByBlock = new HashMap<>();
    private final Map<Integer, String> toolIdsByBlock = new HashMap<>();

    /** Returns the canonical chunk for one SSE data payload, or null when the event carries nothing canonical. */
    ChatChunk onEvent(JsonNode event) {
        return switch (event.path("type").asText()) {
            case "message_start" -> {
                JsonNode message = event.path("message");
                messageId = message.path("id").asText(null);
                model = message.path("model").asText(null);
                inputTokens = message.path("usage").path("input_tokens").asInt();
                cachedTokens = message.path("usage").has("cache_read_input_tokens")
                        ? message.path("usage").path("cache_read_input_tokens").asInt()
                        : null;
                yield null;
            }
            case "content_block_start" -> {
                JsonNode block = event.path("content_block");
                if (!"tool_use".equals(block.path("type").asText())) {
                    yield null;
                }
                int index = event.path("index").asInt();
                toolIdsByBlock.put(index, block.path("id").asText());
                toolNamesByBlock.put(index, block.path("name").asText());
                yield new ChatChunk(
                        messageId,
                        model,
                        null,
                        List.of(new ChatChunk.ToolCallDelta(
                                index, toolIdsByBlock.get(index), toolNamesByBlock.get(index), null)),
                        null,
                        null);
            }
            case "content_block_delta" -> {
                JsonNode delta = event.path("delta");
                int index = event.path("index").asInt();
                yield switch (delta.path("type").asText()) {
                    case "text_delta" ->
                        new ChatChunk(messageId, model, delta.path("text").asText(), null, null, null);
                    case "input_json_delta" ->
                        new ChatChunk(
                                messageId,
                                model,
                                null,
                                List.of(new ChatChunk.ToolCallDelta(
                                        index,
                                        toolIdsByBlock.get(index),
                                        toolNamesByBlock.get(index),
                                        delta.path("partial_json").asText())),
                                null,
                                null);
                    default -> null;
                };
            }
            case "message_delta" ->
                new ChatChunk(
                        messageId,
                        model,
                        null,
                        null,
                        AnthropicTransformer.mapStopReason(
                                event.path("delta").path("stop_reason").asText(null)),
                        new Usage(
                                inputTokens,
                                event.path("usage").path("output_tokens").asInt(),
                                cachedTokens,
                                null));
            default -> null; // ping, content_block_stop, message_stop
        };
    }
}
