package dev.javalitellm.core.exception;

public class ApiTimeoutException extends LiteLlmException {

    public ApiTimeoutException(String message, String provider, String model, Throwable cause) {
        super(message, provider, model, 408, true, cause);
    }
}
