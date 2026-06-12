package dev.javalitellm.callbacks;

import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.exception.LiteLlmException;
import java.time.Duration;

/**
 * Observability hook around every SDK call. Implementations are invoked asynchronously on virtual
 * threads and must not assume ordering across calls; exceptions thrown from hooks are logged and
 * never affect the request path.
 */
public interface LlmCallback {

    default void onRequest(CallContext ctx) {}

    default void onSuccess(CallContext ctx, ChatResponse response, Duration elapsed) {}

    default void onFailure(CallContext ctx, LiteLlmException error, Duration elapsed) {}
}
