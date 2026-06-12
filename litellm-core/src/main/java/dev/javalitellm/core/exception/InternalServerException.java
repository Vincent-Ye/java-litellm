package dev.javalitellm.core.exception;

public class InternalServerException extends LiteLlmException {

    public InternalServerException(String message, String provider, String model) {
        this(message, provider, model, null);
    }

    public InternalServerException(String message, String provider, String model, Throwable cause) {
        super(message, provider, model, 500, true, cause);
    }
}
