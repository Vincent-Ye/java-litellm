package dev.javalitellm.callbacks;

import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.exception.LiteLlmException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Structured request/response logging. Never logs message content or API keys — metadata only. */
public final class LoggingCallback implements LlmCallback {

    private static final Logger log = LoggerFactory.getLogger(LoggingCallback.class);

    @Override
    public void onRequest(CallContext ctx) {
        log.info("llm call start callId={} provider={} model={}", ctx.callId(), ctx.provider(), ctx.model());
    }

    @Override
    public void onSuccess(CallContext ctx, ChatResponse response, Duration elapsed) {
        log.info(
                "llm call ok callId={} provider={} model={} elapsedMs={} promptTokens={} completionTokens={} costUsd={}",
                ctx.callId(),
                ctx.provider(),
                ctx.model(),
                elapsed.toMillis(),
                response.usage() != null ? response.usage().promptTokens() : null,
                response.usage() != null ? response.usage().completionTokens() : null,
                response.costUsd());
    }

    @Override
    public void onFailure(CallContext ctx, LiteLlmException error, Duration elapsed) {
        log.warn(
                "llm call failed callId={} provider={} model={} elapsedMs={} status={} retryable={} message={}",
                ctx.callId(),
                ctx.provider(),
                ctx.model(),
                elapsed.toMillis(),
                error.statusCode(),
                error.retryable(),
                error.getMessage());
    }
}
