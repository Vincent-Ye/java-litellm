package dev.javalitellm.core.exception;

/** Separate type so routers can configure a dedicated fallback to long-context models. */
public class ContextWindowExceededException extends BadRequestException {

    public ContextWindowExceededException(String message, String provider, String model) {
        super(message, provider, model);
    }
}
