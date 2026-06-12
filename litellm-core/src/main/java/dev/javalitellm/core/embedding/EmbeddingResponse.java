package dev.javalitellm.core.embedding;

import dev.javalitellm.core.chat.Usage;
import java.math.BigDecimal;
import java.util.List;

/** Embedding vectors in input order. */
public record EmbeddingResponse(String model, List<float[]> embeddings, Usage usage, BigDecimal costUsd) {

    public EmbeddingResponse {
        embeddings = embeddings == null ? List.of() : List.copyOf(embeddings);
    }
}
