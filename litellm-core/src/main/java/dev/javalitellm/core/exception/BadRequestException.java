package dev.javalitellm.core.exception;

public class BadRequestException extends LiteLlmException {

    public BadRequestException(String message, String provider, String model) {
        this(message, provider, model, null);
    }

    public BadRequestException(String message, String provider, String model, Throwable cause) {
        super(message, provider, model, 400, false, cause);
    }
}
