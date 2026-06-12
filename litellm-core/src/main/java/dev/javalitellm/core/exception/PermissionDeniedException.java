package dev.javalitellm.core.exception;

public class PermissionDeniedException extends LiteLlmException {

    public PermissionDeniedException(String message, String provider, String model) {
        this(message, provider, model, null);
    }

    public PermissionDeniedException(String message, String provider, String model, Throwable cause) {
        super(message, provider, model, 403, false, cause);
    }
}
