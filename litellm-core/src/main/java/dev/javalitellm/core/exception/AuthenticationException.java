package dev.javalitellm.core.exception;

public class AuthenticationException extends LiteLlmException {

    public AuthenticationException(String message, String provider, String model) {
        this(message, provider, model, null);
    }

    public AuthenticationException(String message, String provider, String model, Throwable cause) {
        super(message, provider, model, 401, false, cause);
    }
}
