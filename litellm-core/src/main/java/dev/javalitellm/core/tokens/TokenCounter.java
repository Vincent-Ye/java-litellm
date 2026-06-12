package dev.javalitellm.core.tokens;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import dev.javalitellm.core.chat.Message;
import java.util.List;

/**
 * Token estimation via jtokkit (tiktoken's Java port). Exact for OpenAI tokenizers; other providers
 * use different tokenizers, so treat results as an estimate there and prefer the provider-reported
 * {@code usage} — same behavior as LiteLLM's token_counter.
 */
public final class TokenCounter {

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();

    // Per OpenAI's counting guide: every message carries ~3 tokens of structure, plus 3 priming tokens.
    private static final int TOKENS_PER_MESSAGE = 3;
    private static final int REPLY_PRIMING_TOKENS = 3;

    private TokenCounter() {}

    public static int count(String text, String model) {
        return text == null || text.isEmpty() ? 0 : encodingFor(model).countTokens(text);
    }

    public static int countMessages(List<Message> messages, String model) {
        Encoding encoding = encodingFor(model);
        int total = REPLY_PRIMING_TOKENS;
        for (Message message : messages) {
            total += TOKENS_PER_MESSAGE;
            String text = message.text();
            if (!text.isEmpty()) {
                total += encoding.countTokens(text);
            }
        }
        return total;
    }

    private static Encoding encodingFor(String model) {
        return REGISTRY.getEncoding(encodingTypeFor(model == null ? "" : model.toLowerCase()));
    }

    private static EncodingType encodingTypeFor(String model) {
        if (model.startsWith("gpt-4o")
                || model.startsWith("gpt-4.1")
                || model.startsWith("gpt-5")
                || model.startsWith("o1")
                || model.startsWith("o3")
                || model.startsWith("o4")
                || model.startsWith("chatgpt-4o")) {
            return EncodingType.O200K_BASE;
        }
        return EncodingType.CL100K_BASE;
    }
}
