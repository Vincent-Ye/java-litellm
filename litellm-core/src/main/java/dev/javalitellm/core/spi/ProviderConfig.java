package dev.javalitellm.core.spi;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-call provider settings. {@code apiBase} overrides the provider default endpoint — this is how
 * OpenAI-compatible providers (DeepSeek, Groq, Ollama, vLLM, ...) are reached through provider-openai.
 */
public record ProviderConfig(
        String apiKey, String apiBase, String apiVersion, Duration timeout, Map<String, String> extraHeaders) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    public ProviderConfig {
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String apiKey;
        private String apiBase;
        private String apiVersion;
        private Duration timeout;
        private final Map<String, String> extraHeaders = new LinkedHashMap<>();

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder apiBase(String apiBase) {
            this.apiBase = apiBase;
            return this;
        }

        /** Provider-specific API version, e.g. Azure OpenAI's {@code api-version} query parameter. */
        public Builder apiVersion(String apiVersion) {
            this.apiVersion = apiVersion;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder extraHeader(String name, String value) {
            this.extraHeaders.put(name, value);
            return this;
        }

        public ProviderConfig build() {
            return new ProviderConfig(apiKey, apiBase, apiVersion, timeout, extraHeaders);
        }
    }
}
