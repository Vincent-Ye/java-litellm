package dev.javalitellm.core.spi;

import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.exception.LiteLlmException;

/**
 * Callback for streamed completions. Exactly one of {@link #onComplete()} / {@link #onError} is
 * called last; no {@link #onChunk} call follows either.
 */
public interface StreamHandler {

    void onChunk(ChatChunk chunk);

    default void onComplete() {}

    default void onError(LiteLlmException e) {}
}
