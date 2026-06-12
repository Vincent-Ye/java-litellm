package dev.javalitellm.core.chat;

import com.fasterxml.jackson.databind.JsonNode;

/** A function tool definition. {@code parameters} is a JSON Schema object. */
public record Tool(String name, String description, JsonNode parameters) {}
