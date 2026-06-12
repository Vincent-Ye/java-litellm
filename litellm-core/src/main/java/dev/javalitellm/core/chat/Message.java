package dev.javalitellm.core.chat;

import java.util.List;

/**
 * A chat message in canonical (OpenAI) form.
 *
 * <p>{@code toolCalls} is only present on assistant messages; {@code toolCallId} only on tool-result
 * messages.
 */
public record Message(Role role, List<Content> content, List<ToolCall> toolCalls, String toolCallId) {

    public Message {
        content = content == null ? List.of() : List.copyOf(content);
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
    }

    public static Message system(String text) {
        return new Message(Role.SYSTEM, List.of(Content.text(text)), null, null);
    }

    public static Message user(String text) {
        return new Message(Role.USER, List.of(Content.text(text)), null, null);
    }

    public static Message user(List<Content> content) {
        return new Message(Role.USER, content, null, null);
    }

    public static Message assistant(String text) {
        return new Message(Role.ASSISTANT, List.of(Content.text(text)), null, null);
    }

    public static Message assistantToolCalls(List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, List.of(), toolCalls, null);
    }

    public static Message toolResult(String toolCallId, String text) {
        return new Message(Role.TOOL, List.of(Content.text(text)), null, toolCallId);
    }

    /** Concatenated text of all {@link Content.Text} parts; empty string when there are none. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Content part : content) {
            if (part instanceof Content.Text(String text)) {
                sb.append(text);
            }
        }
        return sb.toString();
    }
}
