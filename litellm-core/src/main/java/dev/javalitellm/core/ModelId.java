package dev.javalitellm.core;

/**
 * A parsed model route string following the LiteLLM convention, e.g. {@code "anthropic/claude-sonnet-4-6"}.
 *
 * <p>A route without a provider prefix defaults to {@link #DEFAULT_PROVIDER}. Providers like Bedrock embed
 * further slashes in the model part ({@code "bedrock/us.anthropic.claude..."}), so only the first slash splits.
 */
public record ModelId(String provider, String model) {

    public static final String DEFAULT_PROVIDER = "openai";

    public ModelId {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
    }

    public static ModelId parse(String route) {
        if (route == null || route.isBlank()) {
            throw new IllegalArgumentException("model route must not be blank");
        }
        int slash = route.indexOf('/');
        if (slash < 0) {
            return new ModelId(DEFAULT_PROVIDER, route);
        }
        return new ModelId(route.substring(0, slash), route.substring(slash + 1));
    }

    @Override
    public String toString() {
        return provider + "/" + model;
    }
}
