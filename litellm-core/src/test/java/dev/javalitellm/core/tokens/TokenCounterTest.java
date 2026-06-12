package dev.javalitellm.core.tokens;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javalitellm.core.chat.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenCounterTest {

    @Test
    void countsKnownStringExactlyForCl100k() {
        // "Hello, world!" is 4 tokens under cl100k_base
        assertThat(TokenCounter.count("Hello, world!", "gpt-4")).isEqualTo(4);
    }

    @Test
    void handlesEmptyAndNullText() {
        assertThat(TokenCounter.count("", "gpt-4o")).isZero();
        assertThat(TokenCounter.count(null, "gpt-4o")).isZero();
    }

    @Test
    void messageCountIncludesStructureOverhead() {
        List<Message> messages = List.of(Message.system("be brief"), Message.user("hi"));
        int textOnly = TokenCounter.count("be brief", "gpt-4o") + TokenCounter.count("hi", "gpt-4o");

        assertThat(TokenCounter.countMessages(messages, "gpt-4o")).isEqualTo(textOnly + 2 * 3 + 3);
    }

    @Test
    void unknownModelFallsBackToCl100k() {
        assertThat(TokenCounter.count("Hello, world!", "claude-sonnet-4-6")).isEqualTo(4);
    }
}
