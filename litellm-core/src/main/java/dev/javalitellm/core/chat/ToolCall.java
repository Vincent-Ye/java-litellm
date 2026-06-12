package dev.javalitellm.core.chat;

/** A function call requested by the model. {@code arguments} is the raw JSON string, as on the wire. */
public record ToolCall(String id, String name, String arguments) {}
