package dev.javalitellm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModelIdTest {

    @Test
    void parsesProviderPrefixedRoute() {
        ModelId id = ModelId.parse("anthropic/claude-sonnet-4-6");
        assertThat(id.provider()).isEqualTo("anthropic");
        assertThat(id.model()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void defaultsToOpenAiWithoutPrefix() {
        ModelId id = ModelId.parse("gpt-4o");
        assertThat(id.provider()).isEqualTo("openai");
        assertThat(id.model()).isEqualTo("gpt-4o");
    }

    @Test
    void splitsOnFirstSlashOnly() {
        ModelId id = ModelId.parse("bedrock/us.anthropic.claude-sonnet-4-6/v1");
        assertThat(id.provider()).isEqualTo("bedrock");
        assertThat(id.model()).isEqualTo("us.anthropic.claude-sonnet-4-6/v1");
    }

    @Test
    void rejectsBlankRoute() {
        assertThatThrownBy(() -> ModelId.parse("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelId.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsToString() {
        assertThat(ModelId.parse("mistral/mistral-large").toString()).isEqualTo("mistral/mistral-large");
    }
}
