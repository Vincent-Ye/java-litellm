package dev.javalitellm.core.chat;

/** Token usage. {@code cachedTokens} / {@code reasoningTokens} are null when the provider does not report them. */
public record Usage(int promptTokens, int completionTokens, Integer cachedTokens, Integer reasoningTokens) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public static Usage of(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens, null, null);
    }
}
