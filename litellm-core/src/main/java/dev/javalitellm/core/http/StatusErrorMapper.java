package dev.javalitellm.core.http;

import dev.javalitellm.core.exception.AuthenticationException;
import dev.javalitellm.core.exception.BadRequestException;
import dev.javalitellm.core.exception.ContextWindowExceededException;
import dev.javalitellm.core.exception.InternalServerException;
import dev.javalitellm.core.exception.LiteLlmException;
import dev.javalitellm.core.exception.NotFoundException;
import dev.javalitellm.core.exception.PermissionDeniedException;
import dev.javalitellm.core.exception.RateLimitException;
import dev.javalitellm.core.exception.ServiceUnavailableException;

/** Default HTTP-status → unified-exception mapping, shared by providers. */
public final class StatusErrorMapper {

    private StatusErrorMapper() {}

    public static LiteLlmException map(int status, String message, String provider, String model) {
        if (isContextWindowError(message)) {
            return new ContextWindowExceededException(message, provider, model);
        }
        return switch (status) {
            case 400, 422 -> new BadRequestException(message, provider, model);
            case 401 -> new AuthenticationException(message, provider, model);
            case 403 -> new PermissionDeniedException(message, provider, model);
            case 404 -> new NotFoundException(message, provider, model);
            case 429 -> new RateLimitException(message, provider, model);
            case 503 -> new ServiceUnavailableException(message, provider, model);
            default ->
                status >= 500
                        ? new InternalServerException(message, provider, model)
                        : new LiteLlmException(message, provider, model, status, false);
        };
    }

    private static boolean isContextWindowError(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("context_length_exceeded")
                || lower.contains("context length")
                || lower.contains("maximum context")
                || lower.contains("prompt is too long");
    }
}
