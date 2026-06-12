package dev.javalitellm.provider.anthropic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.exception.ContextWindowExceededException;
import dev.javalitellm.core.spi.ProviderConfig;
import dev.javalitellm.core.spi.StreamHandler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@WireMockTest
class AnthropicProviderWireMockTest {

    private final AnthropicProvider provider = new AnthropicProvider();

    private static ChatRequest request() {
        return ChatRequest.builder()
                .model("claude-sonnet-4-6")
                .message(Message.user("hi"))
                .build();
    }

    private static ProviderConfig config(WireMockRuntimeInfo wm) {
        return ProviderConfig.builder()
                .apiKey("sk-ant-test")
                .apiBase(wm.getHttpBaseUrl())
                .build();
    }

    @Test
    void chatSendsAnthropicHeaders(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(
                        post(urlEqualTo("/v1/messages"))
                                .withHeader("x-api-key", equalTo("sk-ant-test"))
                                .withHeader("anthropic-version", equalTo("2023-06-01"))
                                .willReturn(
                                        aResponse()
                                                .withStatus(200)
                                                .withBody(
                                                        """
                                        {"id":"msg_1","model":"claude-sonnet-4-6","role":"assistant",
                                         "content":[{"type":"text","text":"hello"}],
                                         "stop_reason":"end_turn",
                                         "usage":{"input_tokens":5,"output_tokens":2}}
                                        """)));

        ChatResponse resp = provider.chat(request(), config(wm));

        assertThat(resp.firstText()).isEqualTo("hello");
        assertThat(resp.usage().totalTokens()).isEqualTo(7);
    }

    @Test
    void chatStreamMapsAnthropicEvents(WireMockRuntimeInfo wm) {
        String sse =
                """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","model":"claude-sonnet-4-6","usage":{"input_tokens":9}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/messages"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/event-stream")
                                .withBody(sse)));

        List<ChatChunk> chunks = new ArrayList<>();
        provider.chatStream(request(), config(wm), new StreamHandler() {
            @Override
            public void onChunk(ChatChunk chunk) {
                chunks.add(chunk);
            }
        });

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).textDelta()).isEqualTo("Hel");
        assertThat(chunks.get(1).textDelta()).isEqualTo("lo");
        ChatChunk last = chunks.get(2);
        assertThat(last.finishReason()).isEqualTo("stop");
        assertThat(last.usage().promptTokens()).isEqualTo(9);
        assertThat(last.usage().completionTokens()).isEqualTo(4);
    }

    @Test
    void streamStitchesToolCallDeltas(WireMockRuntimeInfo wm) {
        String sse =
                """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","model":"claude-sonnet-4-6","usage":{"input_tokens":9}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":":\\"Tokyo\\"}"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":15}}

                """;
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/messages"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/event-stream")
                                .withBody(sse)));

        StringBuilder args = new StringBuilder();
        List<ChatChunk> chunks = new ArrayList<>();
        provider.chatStream(request(), config(wm), new StreamHandler() {
            @Override
            public void onChunk(ChatChunk chunk) {
                chunks.add(chunk);
                for (var delta : chunk.toolCallDeltas()) {
                    if (delta.argumentsDelta() != null) {
                        args.append(delta.argumentsDelta());
                    }
                }
            }
        });

        assertThat(chunks.getFirst().toolCallDeltas().getFirst().name()).isEqualTo("get_weather");
        assertThat(args.toString()).isEqualTo("{\"city\":\"Tokyo\"}");
        assertThat(chunks.getLast().finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void mapsContextWindowErrors(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/messages"))
                        .willReturn(aResponse()
                                .withStatus(400)
                                .withBody("{\"error\":{\"type\":\"invalid_request_error\","
                                        + "\"message\":\"prompt is too long: 250000 tokens > 200000 maximum\"}}")));

        assertThatThrownBy(() -> provider.chat(request(), config(wm)))
                .isInstanceOf(ContextWindowExceededException.class);
    }
}
