package dev.javalitellm.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatRequestTest {

    @Test
    void buildsMinimalRequest() {
        ChatRequest req = ChatRequest.builder()
                .model("anthropic/claude-sonnet-4-6")
                .message(Message.user("hi"))
                .build();
        assertThat(req.model()).isEqualTo("anthropic/claude-sonnet-4-6");
        assertThat(req.messages()).hasSize(1);
        assertThat(req.extraParams()).isEmpty();
    }

    @Test
    void rejectsMissingModelOrMessages() {
        assertThatThrownBy(
                        () -> ChatRequest.builder().message(Message.user("hi")).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChatRequest.builder().model("gpt-4o").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messagesAreImmutable() {
        ChatRequest req = ChatRequest.builder()
                .model("gpt-4o")
                .message(Message.user("hi"))
                .build();
        assertThatThrownBy(() -> req.messages().add(Message.user("more")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toBuilderRoundTrips() {
        ChatRequest original = ChatRequest.builder()
                .model("gpt-4o")
                .message(Message.user("hi"))
                .temperature(0.5)
                .extraParam("logprobs", true)
                .build();
        ChatRequest copy = original.toBuilder().maxTokens(100).build();
        assertThat(copy.model()).isEqualTo("gpt-4o");
        assertThat(copy.temperature()).isEqualTo(0.5);
        assertThat(copy.maxTokens()).isEqualTo(100);
        assertThat(copy.extraParams()).containsEntry("logprobs", true);
        assertThat(original.maxTokens()).isNull();
    }

    @Test
    void messageTextConcatenatesTextParts() {
        Message msg = Message.user(List.of(Content.text("a"), Content.image("https://x/img.png"), Content.text("b")));
        assertThat(msg.text()).isEqualTo("ab");
    }
}
