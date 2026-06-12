package dev.javalitellm.core.exception;

public class RateLimitException extends LiteLlmException {

    public RateLimitException(String message, String provider, String model) {
        this(message, provider, model, null);
    }

    public RateLimitException(String message, String provider, String model, Throwable cause) {
        super(message, provider, model, 429, true, cause);
    }
}
