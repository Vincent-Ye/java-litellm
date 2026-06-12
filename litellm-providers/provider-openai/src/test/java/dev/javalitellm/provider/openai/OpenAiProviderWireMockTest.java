package dev.javalitellm.provider.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
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
import dev.javalitellm.core.exception.AuthenticationException;
import dev.javalitellm.core.exception.LiteLlmException;
import dev.javalitellm.core.exception.RateLimitException;
import dev.javalitellm.core.spi.ProviderConfig;
import dev.javalitellm.core.spi.StreamHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

@WireMockTest
class OpenAiProviderWireMockTest {

    private final OpenAiProvider provider = new OpenAiProvider();

    private static ChatRequest request() {
        return ChatRequest.builder().model("gpt-4o").message(Message.user("hi")).build();
    }

    private static ProviderConfig config(WireMockRuntimeInfo wm) {
        return ProviderConfig.builder()
                .apiKey("sk-test")
                .apiBase(wm.getHttpBaseUrl() + "/v1")
                .build();
    }

    @Test
    void chatSendsAuthHeaderAndParsesResponse(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(
                        post(urlEqualTo("/v1/chat/completions"))
                                .withHeader("Authorization", equalTo("Bearer sk-test"))
                                .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-4o")))
                                .willReturn(
                                        aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(
                                                        """
                                        {"id":"chatcmpl-1","model":"gpt-4o","created":1,
                                         "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},
                                                     "finish_reason":"stop"}],
                                         "usage":{"prompt_tokens":3,"completion_tokens":2}}
                                        """)));

        ChatResponse resp = provider.chat(request(), config(wm));

        assertThat(resp.firstText()).isEqualTo("hello");
        assertThat(resp.usage().totalTokens()).isEqualTo(5);
    }

    @Test
    void chatStreamDeliversDeltasInOrder(WireMockRuntimeInfo wm) {
        String sse =
                """
                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}

                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}

                data: {"id":"c1","model":"gpt-4o","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}

                data: [DONE]

                """;
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/chat/completions"))
                        .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/event-stream")
                                .withBody(sse)));

        List<ChatChunk> chunks = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        provider.chatStream(request(), config(wm), new StreamHandler() {
            @Override
            public void onChunk(ChatChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        assertThat(completed).isTrue();
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).textDelta()).isEqualTo("Hel");
        assertThat(chunks.get(1).finishReason()).isEqualTo("stop");
        assertThat(chunks.get(2).usage()).isNotNull();
    }

    @Test
    void mapsErrorStatusesToUnifiedExceptions(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse().withStatus(429).withBody("{\"error\":{\"message\":\"rate limited\"}}")));

        assertThatThrownBy(() -> provider.chat(request(), config(wm)))
                .isInstanceOf(RateLimitException.class)
                .hasMessage("rate limited")
                .matches(e -> ((LiteLlmException) e).retryable());

        wm.getWireMock()
                .register(post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(401).withBody("{\"error\":{\"message\":\"bad key\"}}")));

        assertThatThrownBy(() -> provider.chat(request(), config(wm)))
                .isInstanceOf(AuthenticationException.class)
                .matches(e -> !((LiteLlmException) e).retryable());
    }

    @Test
    void streamErrorsGoToHandler(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"boom\"}}")));

        List<LiteLlmException> errors = new ArrayList<>();
        provider.chatStream(request(), config(wm), new StreamHandler() {
            @Override
            public void onChunk(ChatChunk chunk) {}

            @Override
            public void onError(LiteLlmException e) {
                errors.add(e);
            }
        });

        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().statusCode()).isEqualTo(500);
    }
}
