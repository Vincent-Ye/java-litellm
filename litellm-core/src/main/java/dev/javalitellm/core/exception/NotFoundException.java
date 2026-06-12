package dev.javalitellm.core.exception;

public class NotFoundException extends LiteLlmException {

    public NotFoundException(String message, String provider, String model) {
        this(message, provider, model, null);
    }

    public NotFoundException(String message, String provider, String model, Throwable cause) {
        super(message, provider, model, 404, false, cause);
    }
}
