package dev.javalitellm.provider.mistral;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.spi.ProviderConfig;
import dev.javalitellm.core.spi.StreamHandler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@WireMockTest
class MistralProviderWireMockTest {

    private final MistralProvider provider = new MistralProvider();

    private static ChatRequest request() {
        return ChatRequest.builder()
                .model("mistral-large-latest")
                .message(Message.user("hi"))
                .build();
    }

    private static ProviderConfig config(WireMockRuntimeInfo wm) {
        return ProviderConfig.builder()
                .apiKey("mistral-key")
                .apiBase(wm.getHttpBaseUrl() + "/v1")
                .build();
    }

    @Test
    void chatUsesBearerAuthAndOpenAiFormat(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(
                        post(urlEqualTo("/v1/chat/completions"))
                                .withHeader("Authorization", equalTo("Bearer mistral-key"))
                                .withRequestBody(matchingJsonPath("$.model", equalTo("mistral-large-latest")))
                                .willReturn(
                                        aResponse()
                                                .withStatus(200)
                                                .withBody(
                                                        """
                                        {"id":"c1","model":"mistral-large-latest","created":1,
                                         "choices":[{"index":0,"message":{"role":"assistant","content":"bonjour"},
                                                     "finish_reason":"stop"}],
                                         "usage":{"prompt_tokens":3,"completion_tokens":2}}
                                        """)));

        ChatResponse resp = provider.chat(request(), config(wm));

        assertThat(resp.firstText()).isEqualTo("bonjour");
    }

    @Test
    void streamingDropsStreamOptions(WireMockRuntimeInfo wm) {
        String sse =
                """
                data: {"id":"c1","model":"mistral-large-latest","choices":[{"index":0,"delta":{"content":"salut"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":1}}

                data: [DONE]

                """;
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/chat/completions"))
                        .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                        .withRequestBody(notMatching(".*stream_options.*"))
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

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().textDelta()).isEqualTo("salut");
        assertThat(chunks.getFirst().usage()).isNotNull();
    }
}
