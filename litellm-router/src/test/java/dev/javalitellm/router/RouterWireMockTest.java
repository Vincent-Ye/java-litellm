package dev.javalitellm.router;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.exception.ApiTimeoutException;
import dev.javalitellm.core.exception.BadRequestException;
import dev.javalitellm.core.exception.ContextWindowExceededException;
import dev.javalitellm.core.exception.RateLimitException;
import dev.javalitellm.core.spi.ProviderConfig;
import dev.javalitellm.core.spi.StreamHandler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fault-injection tests: each "deployment" is a distinct path prefix on one WireMock server,
 * reached through provider-openai's apiBase override.
 */
@WireMockTest
class RouterWireMockTest {

    private static final String OK_BODY =
            """
            {"id":"c1","model":"gpt-4o","created":1,
             "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":2}}
            """;

    private static Deployment deployment(String id, String group, WireMockRuntimeInfo wm) {
        return Deployment.builder()
                .id(id)
                .modelGroup(group)
                .model("openai/gpt-4o")
                .config(ProviderConfig.builder()
                        .apiKey("sk-test")
                        .apiBase(wm.getHttpBaseUrl() + "/" + id + "/v1")
                        .build())
                .build();
    }

    private static ChatRequest request(String group) {
        return ChatRequest.builder().model(group).message(Message.user("hi")).build();
    }

    private static void stubOk(WireMockRuntimeInfo wm, String id, String text) {
        wm.getWireMock()
                .register(post(urlEqualTo("/" + id + "/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(200).withBody(OK_BODY.formatted(text))));
    }

    private static void stubError(WireMockRuntimeInfo wm, String id, int status, String message) {
        wm.getWireMock()
                .register(post(urlEqualTo("/" + id + "/v1/chat/completions"))
                        .willReturn(aResponse()
                                .withStatus(status)
                                .withBody("{\"error\":{\"message\":\"" + message + "\"}}")));
    }

    @Test
    void failsOverToNextDeploymentAndCoolsDown429(WireMockRuntimeInfo wm) {
        stubError(wm, "a", 429, "rate limited");
        stubOk(wm, "b", "from-b");

        Router router = Router.builder()
                .deployment(deployment("a", "gpt-4o", wm))
                .deployment(deployment("b", "gpt-4o", wm))
                .strategy(RoutingStrategy.latencyBased()) // deterministic enough: untried first
                .build();

        // run twice so whichever order the strategy tries, deployment a fails once
        ChatResponse first = router.chat(request("gpt-4o"));
        ChatResponse second = router.chat(request("gpt-4o"));

        assertThat(first.firstText()).isEqualTo("from-b");
        assertThat(second.firstText()).isEqualTo("from-b");
        assertThat(router.state().isCoolingDown("a")).isTrue();
        assertThat(router.state().isCoolingDown("b")).isFalse();
    }

    @Test
    void fallsBackToSecondGroupWhenPrimaryExhausted(WireMockRuntimeInfo wm) {
        stubError(wm, "primary-1", 500, "boom");
        stubOk(wm, "backup-1", "from-backup");

        Router router = Router.builder()
                .deployment(deployment("primary-1", "gpt-4o", wm))
                .deployment(deployment("backup-1", "claude-fallback", wm))
                .fallback("gpt-4o", List.of("claude-fallback"))
                .build();

        assertThat(router.chat(request("gpt-4o")).firstText()).isEqualTo("from-backup");
    }

    @Test
    void contextWindowErrorJumpsToContextFallbacks(WireMockRuntimeInfo wm) {
        stubError(wm, "small", 400, "This model's maximum context length is 8192 tokens");
        stubOk(wm, "large", "from-large-context");

        Router router = Router.builder()
                .deployment(deployment("small", "gpt-4o", wm))
                .deployment(deployment("large", "long-context", wm))
                .contextWindowFallback("gpt-4o", List.of("long-context"))
                .build();

        assertThat(router.chat(request("gpt-4o")).firstText()).isEqualTo("from-large-context");
    }

    @Test
    void contextWindowErrorWithoutFallbackPropagates(WireMockRuntimeInfo wm) {
        stubError(wm, "small", 400, "maximum context length exceeded");

        Router router =
                Router.builder().deployment(deployment("small", "gpt-4o", wm)).build();

        assertThatThrownBy(() -> router.chat(request("gpt-4o"))).isInstanceOf(ContextWindowExceededException.class);
    }

    @Test
    void nonRetryableErrorFailsFastWithoutTryingOthers(WireMockRuntimeInfo wm) {
        stubError(wm, "bad", 400, "invalid request");
        stubOk(wm, "unused", "never");

        Router router = Router.builder()
                .deployment(deployment("bad", "gpt-4o", wm))
                .deployment(deployment("unused", "gpt-4o", wm))
                .strategy((candidates, state) -> candidates.stream()
                        .filter(d -> d.id().equals("bad"))
                        .findFirst()
                        .orElse(candidates.getFirst()))
                .build();

        assertThatThrownBy(() -> router.chat(request("gpt-4o"))).isInstanceOf(BadRequestException.class);
        assertThat(wm.getWireMock().getServeEvents()).hasSize(1);
    }

    @Test
    void cooledDownDeploymentRecoversAfterExpiry(WireMockRuntimeInfo wm) throws InterruptedException {
        wm.getWireMock()
                .register(post(urlEqualTo("/solo/v1/chat/completions"))
                        .inScenario("recover")
                        .whenScenarioStateIs(STARTED)
                        .willReturn(aResponse().withStatus(429).withBody("{\"error\":{\"message\":\"slow down\"}}"))
                        .willSetStateTo("healthy"));
        wm.getWireMock()
                .register(post(urlEqualTo("/solo/v1/chat/completions"))
                        .inScenario("recover")
                        .whenScenarioStateIs("healthy")
                        .willReturn(aResponse().withStatus(200).withBody(OK_BODY.formatted("recovered"))));

        Router router = Router.builder()
                .deployment(deployment("solo", "gpt-4o", wm))
                .cooldown(Duration.ofMillis(100))
                .build();

        assertThatThrownBy(() -> router.chat(request("gpt-4o"))).isInstanceOf(RateLimitException.class);
        // while cooling down there is no healthy deployment
        assertThatThrownBy(() -> router.chat(request("gpt-4o"))).hasMessageContaining("no deployment available");
        Thread.sleep(150);
        assertThat(router.chat(request("gpt-4o")).firstText()).isEqualTo("recovered");
    }

    @Test
    void totalTimeoutBudgetIsEnforced(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(post(urlEqualTo("/slow/v1/chat/completions"))
                        .willReturn(aResponse()
                                .withStatus(429)
                                .withFixedDelay(300)
                                .withBody("{\"error\":{\"message\":\"slow down\"}}")));
        stubError(wm, "slow2", 429, "also limited");

        Router router = Router.builder()
                .deployment(deployment("slow", "gpt-4o", wm))
                .deployment(deployment("slow2", "gpt-4o", wm))
                .strategy((candidates, state) -> candidates.stream()
                        .filter(d -> d.id().equals("slow"))
                        .findFirst()
                        .orElse(candidates.getFirst()))
                .totalTimeout(Duration.ofMillis(250))
                .build();

        assertThatThrownBy(() -> router.chat(request("gpt-4o")))
                .isInstanceOf(ApiTimeoutException.class)
                .hasMessageContaining("budget");
    }

    @Test
    void streamFailsOverBeforeFirstChunk(WireMockRuntimeInfo wm) {
        stubError(wm, "sa", 500, "boom");
        String sse =
                """
                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Hey"},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        wm.getWireMock()
                .register(post(urlEqualTo("/sb/v1/chat/completions"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "text/event-stream")
                                .withBody(sse)));

        Router router = Router.builder()
                .deployment(deployment("sa", "gpt-4o", wm))
                .deployment(deployment("sb", "gpt-4o", wm))
                .strategy((candidates, state) -> candidates.stream()
                        .filter(d -> d.id().equals("sa"))
                        .findFirst()
                        .orElse(candidates.getFirst()))
                .build();

        List<ChatChunk> chunks = new ArrayList<>();
        boolean[] completed = new boolean[1];
        router.chatStream(request("gpt-4o"), new StreamHandler() {
            @Override
            public void onChunk(ChatChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onComplete() {
                completed[0] = true;
            }
        });

        assertThat(completed[0]).isTrue();
        assertThat(chunks.getFirst().textDelta()).isEqualTo("Hey");
    }
}
