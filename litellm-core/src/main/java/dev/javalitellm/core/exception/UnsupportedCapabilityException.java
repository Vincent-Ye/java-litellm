package dev.javalitellm.core.exception;

import dev.javalitellm.core.spi.Capability;

public class UnsupportedCapabilityException extends LiteLlmException {

    public UnsupportedCapabilityException(String provider, String model, Capability capability) {
        super("provider '" + provider + "' does not support " + capability, provider, model, 400, false, null);
    }
}
