package dev.javalitellm.core.chat;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL;

    @JsonValue
    public String wireName() {
        return name().toLowerCase();
    }
}
