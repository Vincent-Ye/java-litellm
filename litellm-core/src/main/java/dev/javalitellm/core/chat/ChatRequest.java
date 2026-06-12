package dev.javalitellm.core.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A chat completion request in canonical (OpenAI) form.
 *
 * <p>{@code model} is the full route string, e.g. {@code "anthropic/claude-sonnet-4-6"}.
 * {@code extraParams} carries provider-specific parameters that have no canonical field; transformers
 * pass them through to the wire request.
 */
public record ChatRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Double topP,
        Integer maxTokens,
        List<String> stop,
        List<Tool> tools,
        ToolChoice toolChoice,
        ResponseFormat responseFormat,
        String user,
        Map<String, Object> extraParams) {

    public ChatRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
        stop = stop == null ? null : List.copyOf(stop);
        tools = tools == null ? null : List.copyOf(tools);
        extraParams = extraParams == null ? Map.of() : Map.copyOf(extraParams);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.model = model;
        b.messages = new ArrayList<>(messages);
        b.temperature = temperature;
        b.topP = topP;
        b.maxTokens = maxTokens;
        b.stop = stop;
        b.tools = tools == null ? null : new ArrayList<>(tools);
        b.toolChoice = toolChoice;
        b.responseFormat = responseFormat;
        b.user = user;
        b.extraParams = new LinkedHashMap<>(extraParams);
        return b;
    }

    public static final class Builder {
        private String model;
        private List<Message> messages = new ArrayList<>();
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
        private List<String> stop;
        private List<Tool> tools;
        private ToolChoice toolChoice;
        private ResponseFormat responseFormat;
        private String user;
        private Map<String, Object> extraParams = new LinkedHashMap<>();

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<Message> messages) {
            this.messages = new ArrayList<>(messages);
            return this;
        }

        public Builder message(Message message) {
            this.messages.add(message);
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder stop(List<String> stop) {
            this.stop = stop;
            return this;
        }

        public Builder tools(List<Tool> tools) {
            this.tools = tools;
            return this;
        }

        public Builder toolChoice(ToolChoice toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        public Builder responseFormat(ResponseFormat responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        public Builder user(String user) {
            this.user = user;
            return this;
        }

        public Builder extraParam(String key, Object value) {
            this.extraParams.put(key, value);
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(
                    model,
                    messages,
                    temperature,
                    topP,
                    maxTokens,
                    stop,
                    tools,
                    toolChoice,
                    responseFormat,
                    user,
                    extraParams);
        }
    }
}
