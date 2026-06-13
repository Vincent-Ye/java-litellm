package dev.javalitellm.provider.bedrock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.Usage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamMetadataEvent;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStopEvent;

/**
 * Stateful mapper for one Converse stream: SDK event-stream events to canonical chunks. Tool-use
 * blocks announce id/name in contentBlockStart, then stream argument JSON fragments in
 * contentBlockDelta; usage arrives in the trailing metadata event.
 */
final class BedrockStreamParser {

    private final String modelId;
    private final Map<Integer, String> toolIdsByBlock = new HashMap<>();
    private final Map<Integer, String> toolNamesByBlock = new HashMap<>();

    BedrockStreamParser(String modelId, ObjectMapper ignored) {
        this.modelId = modelId;
    }

    ChatChunk onContentBlockStart(ContentBlockStartEvent event) {
        if (event.start() == null || event.start().toolUse() == null) {
            return null;
        }
        int index = event.contentBlockIndex();
        toolIdsByBlock.put(index, event.start().toolUse().toolUseId());
        toolNamesByBlock.put(index, event.start().toolUse().name());
        return new ChatChunk(
                null,
                modelId,
                null,
                List.of(new ChatChunk.ToolCallDelta(
                        index, toolIdsByBlock.get(index), toolNamesByBlock.get(index), null)),
                null,
                null);
    }

    ChatChunk onContentBlockDelta(ContentBlockDeltaEvent event) {
        if (event.delta() == null) {
            return null;
        }
        if (event.delta().text() != null) {
            return new ChatChunk(null, modelId, event.delta().text(), null, null, null);
        }
        if (event.delta().toolUse() != null) {
            int index = event.contentBlockIndex();
            return new ChatChunk(
                    null,
                    modelId,
                    null,
                    List.of(new ChatChunk.ToolCallDelta(
                            index,
                            toolIdsByBlock.get(index),
                            toolNamesByBlock.get(index),
                            event.delta().toolUse().input())),
                    null,
                    null);
        }
        return null;
    }

    ChatChunk onMessageStop(MessageStopEvent event) {
        return new ChatChunk(null, modelId, null, null, BedrockTransformer.mapStopReason(event.stopReason()), null);
    }

    ChatChunk onMetadata(ConverseStreamMetadataEvent event) {
        if (event.usage() == null) {
            return null;
        }
        Usage usage = new Usage(
                event.usage().inputTokens(),
                event.usage().outputTokens(),
                event.usage().cacheReadInputTokens(),
                null);
        return new ChatChunk(null, modelId, null, null, null, usage);
    }
}
