package dev.javalitellm.callbacks;

import dev.javalitellm.core.chat.ChatRequest;
import java.time.Instant;
import java.util.UUID;

/** Immutable per-call descriptor passed to every callback hook. {@code model} is the full route string. */
public record CallContext(String callId, String provider, String model, ChatRequest request, Instant startedAt) {

    public static CallContext create(String provider, ChatRequest request) {
        return new CallContext(UUID.randomUUID().toString(), provider, request.model(), request, Instant.now());
    }
}
