package dev.javalitellm.router;

import dev.javalitellm.core.spi.ProviderConfig;

/**
 * One concrete deployment behind a model group: the actual route string plus its credentials and
 * optional weight/throughput limits.
 *
 * <p>Example: model group {@code "gpt-4o"} may contain an OpenAI deployment
 * ({@code model="openai/gpt-4o"}) and an Azure one ({@code model="azure/my-gpt4o"}), each with its
 * own {@link ProviderConfig}.
 */
public record Deployment(
        String id, String modelGroup, String model, ProviderConfig config, int weight, Integer tpm, Integer rpm) {

    public Deployment {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("deployment id must not be blank");
        }
        if (modelGroup == null || modelGroup.isBlank()) {
            throw new IllegalArgumentException("modelGroup must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be >= 1");
        }
        config = config == null ? ProviderConfig.builder().build() : config;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String modelGroup;
        private String model;
        private ProviderConfig config;
        private int weight = 1;
        private Integer tpm;
        private Integer rpm;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder modelGroup(String modelGroup) {
            this.modelGroup = modelGroup;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder config(ProviderConfig config) {
            this.config = config;
            return this;
        }

        public Builder weight(int weight) {
            this.weight = weight;
            return this;
        }

        public Builder tpm(Integer tpm) {
            this.tpm = tpm;
            return this;
        }

        public Builder rpm(Integer rpm) {
            this.rpm = rpm;
            return this;
        }

        public Deployment build() {
            return new Deployment(id, modelGroup, model, config, weight, tpm, rpm);
        }
    }
}
