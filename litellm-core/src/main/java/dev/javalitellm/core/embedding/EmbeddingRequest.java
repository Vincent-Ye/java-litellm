package dev.javalitellm.core.embedding;

import java.util.List;
import java.util.Map;

public record EmbeddingRequest(String model, List<String> input, Integer dimensions, Map<String, Object> extraParams) {

    public EmbeddingRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("input must not be empty");
        }
        input = List.copyOf(input);
        extraParams = extraParams == null ? Map.of() : Map.copyOf(extraParams);
    }

    public static EmbeddingRequest of(String model, List<String> input) {
        return new EmbeddingRequest(model, input, null, null);
    }
}
