package dev.javalitellm.core.exception;

/**
 * Base of the unified exception hierarchy. Provider-specific errors are mapped to OpenAI-style
 * exceptions so callers handle one error model regardless of the upstream provider.
 *
 * <p>{@link #retryable()} drives client retries and router cooldown/fallback decisions.
 */
public class LiteLlmException extends RuntimeException {

    private final String provider;
    private final String model;
    private final int statusCode;
    private final boolean retryable;

    public LiteLlmException(String message, String provider, String model, int statusCode, boolean retryable) {
        this(message, provider, model, statusCode, retryable, null);
    }

    public LiteLlmException(
            String message, String provider, String model, int statusCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.model = model;
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
