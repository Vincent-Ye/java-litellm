package dev.javalitellm.core.chat;

import java.math.BigDecimal;
import java.util.List;

/**
 * A chat completion in canonical (OpenAI) form. {@code costUsd} is computed by the SDK from the
 * model price table and may be null when the model is unknown to the table.
 */
public record ChatResponse(
        String id, String model, long created, List<Choice> choices, Usage usage, BigDecimal costUsd) {

    public ChatResponse {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    /** Text of the first choice — the common case. Empty string when there are no choices. */
    public String firstText() {
        return choices.isEmpty() ? "" : choices.getFirst().message().text();
    }
}
