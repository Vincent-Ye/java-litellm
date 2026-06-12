package dev.javalitellm.core.chat;

import com.fasterxml.jackson.databind.JsonNode;

/** Structured-output constraint. {@code jsonSchema} is only set when {@code type == JSON_SCHEMA}. */
public record ResponseFormat(Type type, String schemaName, JsonNode jsonSchema) {

    public enum Type {
        TEXT,
        JSON_OBJECT,
        JSON_SCHEMA
    }

    public static final ResponseFormat TEXT = new ResponseFormat(Type.TEXT, null, null);
    public static final ResponseFormat JSON_OBJECT = new ResponseFormat(Type.JSON_OBJECT, null, null);

    public static ResponseFormat jsonSchema(String name, JsonNode schema) {
        return new ResponseFormat(Type.JSON_SCHEMA, name, schema);
    }
}
