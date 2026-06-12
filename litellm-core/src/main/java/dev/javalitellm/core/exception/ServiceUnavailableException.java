package dev.javalitellm.core.exception;

public class ServiceUnavailableException extends LiteLlmException {

    public ServiceUnavailableException(String message, String provider, String model) {
        this(message, provider, model, null);
    }

    public ServiceUnavailableException(String message, String provider, String model, Throwable cause) {
        super(message, provider, model, 503, true, cause);
    }
}
