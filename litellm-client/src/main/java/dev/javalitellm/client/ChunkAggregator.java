package dev.javalitellm.client;

import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Choice;
import dev.javalitellm.core.chat.Content;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.chat.Role;
import dev.javalitellm.core.chat.ToolCall;
import dev.javalitellm.core.chat.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Rebuilds a complete {@link ChatResponse} from streamed chunks: text deltas concatenate, tool-call
 * fragments stitch per index, the last finish reason and usage win. Not thread-safe; chunks must be
 * fed in stream order.
 */
final class ChunkAggregator {

    private static final class ToolCallParts {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private final StringBuilder text = new StringBuilder();
    private final TreeMap<Integer, ToolCallParts> toolCalls = new TreeMap<>();
    private String id;
    private String model;
    private String finishReason;
    private Usage usage;

    void add(ChatChunk chunk) {
        if (id == null) {
            id = chunk.id();
        }
        if (model == null) {
            model = chunk.model();
        }
        text.append(chunk.textDelta());
        for (ChatChunk.ToolCallDelta delta : chunk.toolCallDeltas()) {
            ToolCallParts parts = toolCalls.computeIfAbsent(delta.index(), k -> new ToolCallParts());
            if (parts.id == null) {
                parts.id = delta.id();
            }
            if (parts.name == null) {
                parts.name = delta.name();
            }
            if (delta.argumentsDelta() != null) {
                parts.arguments.append(delta.argumentsDelta());
            }
        }
        if (chunk.finishReason() != null) {
            finishReason = chunk.finishReason();
        }
        if (chunk.usage() != null) {
            usage = chunk.usage();
        }
    }

    ChatResponse toResponse() {
        List<Content> content = text.isEmpty() ? List.of() : List.of(Content.text(text.toString()));
        List<ToolCall> calls = null;
        if (!toolCalls.isEmpty()) {
            calls = new ArrayList<>();
            for (ToolCallParts parts : toolCalls.values()) {
                calls.add(new ToolCall(parts.id, parts.name, parts.arguments.toString()));
            }
        }
        Message message = new Message(Role.ASSISTANT, content, calls, null);
        return new ChatResponse(id, model, 0, List.of(new Choice(0, message, finishReason)), usage, null);
    }
}
