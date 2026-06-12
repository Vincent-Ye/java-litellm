package dev.javalitellm.core.chat;

import java.util.List;

/**
 * One streamed delta. {@code textDelta} may be empty for chunks that only carry tool-call deltas,
 * the finish reason or usage (typically the last chunk).
 */
public record ChatChunk(
        String id,
        String model,
        String textDelta,
        List<ToolCallDelta> toolCallDeltas,
        String finishReason,
        Usage usage) {

    public ChatChunk {
        textDelta = textDelta == null ? "" : textDelta;
        toolCallDeltas = toolCallDeltas == null ? List.of() : List.copyOf(toolCallDeltas);
    }

    /** Incremental piece of a tool call; {@code argumentsDelta} fragments concatenate into the full JSON. */
    public record ToolCallDelta(int index, String id, String name, String argumentsDelta) {}
}
