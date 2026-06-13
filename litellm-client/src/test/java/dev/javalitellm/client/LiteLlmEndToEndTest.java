package dev.javalitellm.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.exception.BadRequestException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

@WireMockTest
class LiteLlmEndToEndTest {

    private static final String OK_BODY =
            """
            {"id":"chatcmpl-1","model":"gpt-4o","created":1,
             "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":2}}
            """;

    private static LiteLlm client(WireMockRuntimeInfo wm) {
        return LiteLlm.builder()
                .apiKey("openai", "sk-test")
                .apiBase("openai", wm.getHttpBaseUrl() + "/v1")
                .retryPolicy(new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(2)))
                .build();
    }

    private static ChatRequest request() {
        return ChatRequest.builder()
                .model("openai/gpt-4o")
                .message(Message.user("hi"))
                .build();
    }

    @Test
    void routesByModelPrefixAndStripsIt(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(200).withBody(OK_BODY)));

        ChatResponse resp = client(wm).chat(request());

        assertThat(resp.firstText()).isEqualTo("hello");
        // 3 prompt tokens * 2.5e-6 + 2 completion tokens * 1e-5 (gpt-4o bundled prices)
        assertThat(resp.costUsd()).isEqualByComparingTo("0.0000275");
    }

    @Test
    void retriesRetryableErrorsThenSucceeds(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/chat/completions"))
                        .inScenario("retry")
                        .whenScenarioStateIs(STARTED)
                        .willReturn(aResponse().withStatus(429).withBody("{\"error\":{\"message\":\"slow down\"}}"))
                        .willSetStateTo("second"));
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/chat/completions"))
                        .inScenario("retry")
                        .whenScenarioStateIs("second")
                        .willReturn(aResponse().withStatus(200).withBody(OK_BODY)));

        ChatResponse resp = client(wm).chat(request());

        assertThat(resp.firstText()).isEqualTo("hello");
    }

    @Test
    void doesNotRetryNonRetryableErrors(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(400).withBody("{\"error\":{\"message\":\"bad\"}}")));

        assertThatThrownBy(() -> client(wm).chat(request())).isInstanceOf(BadRequestException.class);
        assertThat(wm.getWireMock().getServeEvents()).hasSize(1);
    }

    @Test
    void streamsAsLazyJavaStream(WireMockRuntimeInfo wm) {
        String sse =
                """
                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"He"},"finish_reason":null}]}

                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"y"},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/event-stream")
                                .withBody(sse)));

        StringBuilder text = new StringBuilder();
        client(wm).chatStream(request()).forEach(chunk -> text.append(chunk.textDelta()));

        assertThat(text.toString()).isEqualTo("Hey");
    }

    @Test
    void callbacksReceiveSuccessWithCost(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(200).withBody(OK_BODY)));

        var successes = new java.util.concurrent.CopyOnWriteArrayList<ChatResponse>();
        var latch = new java.util.concurrent.CountDownLatch(2); // onRequest + onSuccess
        LiteLlm client = LiteLlm.builder()
                .apiKey("openai", "sk-test")
                .apiBase("openai", wm.getHttpBaseUrl() + "/v1")
                .callback(new dev.javalitellm.callbacks.LlmCallback() {
                    @Override
                    public void onRequest(dev.javalitellm.callbacks.CallContext ctx) {
                        latch.countDown();
                    }

                    @Override
                    public void onSuccess(
                            dev.javalitellm.callbacks.CallContext ctx, ChatResponse response, Duration elapsed) {
                        successes.add(response);
                        latch.countDown();
                    }
                })
                .build();

        client.chat(request());

        assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(successes.getFirst().costUsd()).isNotNull();
    }

    @Test
    void streamCallbackReceivesAggregatedResponseWithCost(WireMockRuntimeInfo wm) throws Exception {
        String sse =
                """
                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"He"},"finish_reason":null}]}

                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"y"},"finish_reason":"stop"}]}

                data: {"id":"c1","model":"gpt-4o","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}

                data: [DONE]

                """;
        wm.getWireMock()
                .register(post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/event-stream")
                                .withBody(sse)));

        var aggregated = new java.util.concurrent.atomic.AtomicReference<ChatResponse>();
        var latch = new java.util.concurrent.CountDownLatch(1);
        LiteLlm client = LiteLlm.builder()
                .apiKey("openai", "sk-test")
                .apiBase("openai", wm.getHttpBaseUrl() + "/v1")
                .callback(new dev.javalitellm.callbacks.LlmCallback() {
                    @Override
                    public void onStreamComplete(
                            dev.javalitellm.callbacks.CallContext ctx, ChatResponse response, Duration elapsed) {
                        aggregated.set(response);
                        latch.countDown();
                    }
                })
                .build();

        client.chatStream(request()).forEach(chunk -> {});

        assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(aggregated.get().firstText()).isEqualTo("Hey");
        assertThat(aggregated.get().choices().getFirst().finishReason()).isEqualTo("stop");
        assertThat(aggregated.get().usage().totalTokens()).isEqualTo(5);
        assertThat(aggregated.get().costUsd()).isNotNull();
    }

    @Test
    void unknownProviderFailsFast(WireMockRuntimeInfo wm) {
        assertThatThrownBy(() -> client(wm)
                        .chat(ChatRequest.builder()
                                .model("nope/some-model")
                                .message(Message.user("hi"))
                                .build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no provider 'nope'");
    }
}
