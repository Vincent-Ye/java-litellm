package dev.javalitellm.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Choice;
import dev.javalitellm.core.chat.Content;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.chat.Role;
import dev.javalitellm.core.chat.ToolCall;
import dev.javalitellm.core.chat.Usage;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatResponseCodecTest {

    private final ChatResponseCodec codec = new ChatResponseCodec(new ObjectMapper());

    @Test
    void roundTripsTextResponseWithCostAndUsage() {
        ChatResponse original = new ChatResponse(
                "chatcmpl-1",
                "gpt-4o",
                1234L,
                List.of(new Choice(0, new Message(Role.ASSISTANT, List.of(Content.text("hello")), null, null), "stop")),
                new Usage(10, 5, 2, null),
                new BigDecimal("0.0001234"));

        ChatResponse decoded = codec.decode(codec.encode(original));

        assertThat(decoded.id()).isEqualTo("chatcmpl-1");
        assertThat(decoded.model()).isEqualTo("gpt-4o");
        assertThat(decoded.firstText()).isEqualTo("hello");
        assertThat(decoded.choices().getFirst().finishReason()).isEqualTo("stop");
        assertThat(decoded.usage().promptTokens()).isEqualTo(10);
        assertThat(decoded.usage().cachedTokens()).isEqualTo(2);
        assertThat(decoded.costUsd()).isEqualByComparingTo("0.0001234");
    }

    @Test
    void roundTripsMultimodalContentParts() {
        ChatResponse original = new ChatResponse(
                "id",
                "gpt-4o",
                0L,
                List.of(new Choice(
                        0,
                        new Message(
                                Role.ASSISTANT,
                                List.of(
                                        Content.text("see this"),
                                        new Content.Image("https://x/img.png", "high"),
                                        new Content.Audio("base64data", "wav")),
                                null,
                                null),
                        "stop")),
                null,
                null);

        ChatResponse decoded = codec.decode(codec.encode(original));

        List<Content> parts = decoded.choices().getFirst().message().content();
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0)).isInstanceOf(Content.Text.class);
        assertThat(parts.get(1)).isInstanceOf(Content.Image.class);
        assertThat(((Content.Image) parts.get(1)).detail()).isEqualTo("high");
        assertThat(parts.get(2)).isInstanceOf(Content.Audio.class);
        assertThat(((Content.Audio) parts.get(2)).format()).isEqualTo("wav");
    }

    @Test
    void roundTripsToolCalls() {
        ChatResponse original = new ChatResponse(
                "id",
                "gpt-4o",
                0L,
                List.of(new Choice(
                        0,
                        new Message(
                                Role.ASSISTANT,
                                List.of(),
                                List.of(new ToolCall("call_1", "get_weather", "{\"city\":\"Tokyo\"}")),
                                null),
                        "tool_calls")),
                null,
                null);

        ChatResponse decoded = codec.decode(codec.encode(original));

        ToolCall call = decoded.choices().getFirst().message().toolCalls().getFirst();
        assertThat(call.id()).isEqualTo("call_1");
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.arguments()).isEqualTo("{\"city\":\"Tokyo\"}");
    }

    @Test
    void roundTripsToolResultMessage() {
        ChatResponse original = new ChatResponse(
                "id",
                "gpt-4o",
                0L,
                List.of(new Choice(
                        0, new Message(Role.TOOL, List.of(Content.text("22C sunny")), null, "call_1"), null)),
                null,
                null);

        ChatResponse decoded = codec.decode(codec.encode(original));

        Message msg = decoded.choices().getFirst().message();
        assertThat(msg.role()).isEqualTo(Role.TOOL);
        assertThat(msg.toolCallId()).isEqualTo("call_1");
        assertThat(msg.text()).isEqualTo("22C sunny");
    }
}
