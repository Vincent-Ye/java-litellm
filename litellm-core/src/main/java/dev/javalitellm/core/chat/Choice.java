package dev.javalitellm.core.chat;

/** {@code finishReason} keeps the wire value ("stop", "length", "tool_calls", ...) for forward compatibility. */
public record Choice(int index, Message message, String finishReason) {}
