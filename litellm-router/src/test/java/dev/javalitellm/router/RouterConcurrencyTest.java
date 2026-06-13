package dev.javalitellm.router;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.spi.ProviderConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@WireMockTest
class RouterConcurrencyTest {

    @Test
    void thousandConcurrentVirtualThreadCallsKeepStateConsistent(WireMockRuntimeInfo wm) throws Exception {
        for (String id : List.of("a", "b")) {
            wm.getWireMock()
                    .register(
                            post(urlEqualTo("/" + id + "/v1/chat/completions"))
                                    .willReturn(
                                            aResponse()
                                                    .withStatus(200)
                                                    .withBody(
                                                            """
                                            {"id":"c1","model":"gpt-4o","created":1,
                                             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
                                                         "finish_reason":"stop"}],
                                             "usage":{"prompt_tokens":3,"completion_tokens":2}}
                                            """)));
        }

        Router router = Router.builder()
                .deployment(deployment("a", wm))
                .deployment(deployment("b", wm))
                .strategy(RoutingStrategy.leastBusy())
                .build();

        AtomicInteger successes = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                futures.add(executor.submit(() -> {
                    var resp = router.chat(ChatRequest.builder()
                            .model("gpt-4o")
                            .message(Message.user("hi"))
                            .build());
                    if ("ok".equals(resp.firstText())) {
                        successes.incrementAndGet();
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(successes.get()).isEqualTo(1000);
        assertThat(router.state().inFlight("a")).isZero();
        assertThat(router.state().inFlight("b")).isZero();
        assertThat(router.state().requestsLastMinute("a") + router.state().requestsLastMinute("b"))
                .isEqualTo(1000);
    }

    private static Deployment deployment(String id, WireMockRuntimeInfo wm) {
        return Deployment.builder()
                .id(id)
                .modelGroup("gpt-4o")
                .model("openai/gpt-4o")
                .config(ProviderConfig.builder()
                        .apiKey("sk-test")
                        .apiBase(wm.getHttpBaseUrl() + "/" + id + "/v1")
                        .build())
                .build();
    }
}
