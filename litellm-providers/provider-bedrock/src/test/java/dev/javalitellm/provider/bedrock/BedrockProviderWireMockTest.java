package dev.javalitellm.provider.bedrock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.exception.RateLimitException;
import dev.javalitellm.core.spi.ProviderConfig;
import org.junit.jupiter.api.Test;

@WireMockTest
class BedrockProviderWireMockTest {

    private static final String MODEL = "us.anthropic.claude-sonnet-4-6";

    private final BedrockProvider provider = new BedrockProvider();

    private static ChatRequest request() {
        return ChatRequest.builder().model(MODEL).message(Message.user("hi")).build();
    }

    private static ProviderConfig config(WireMockRuntimeInfo wm) {
        return ProviderConfig.builder()
                .apiKey("AKIATEST:secretkey")
                .region("us-east-1")
                .apiBase(wm.getHttpBaseUrl())
                .build();
    }

    @Test
    void chatSignsWithSigV4AndParsesConverseResponse(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(
                        post(urlEqualTo("/model/" + MODEL + "/converse"))
                                .withHeader("Authorization", matching("AWS4-HMAC-SHA256.*"))
                                .willReturn(
                                        aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(
                                                        """
                                        {"output":{"message":{"role":"assistant","content":[{"text":"hello"}]}},
                                         "stopReason":"end_turn",
                                         "usage":{"inputTokens":3,"outputTokens":2,"totalTokens":5},
                                         "metrics":{"latencyMs":42}}
                                        """)));

        ChatResponse resp = provider.chat(request(), config(wm));

        assertThat(resp.firstText()).isEqualTo("hello");
        assertThat(resp.choices().getFirst().finishReason()).isEqualTo("stop");
        assertThat(resp.usage().totalTokens()).isEqualTo(5);
    }

    @Test
    void mapsThrottlingToRateLimitException(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(post(urlEqualTo("/model/" + MODEL + "/converse"))
                        .willReturn(aResponse()
                                .withStatus(429)
                                .withHeader("x-amzn-ErrorType", "ThrottlingException")
                                .withBody("{\"message\":\"Too many requests, please wait\"}")));

        assertThatThrownBy(() -> provider.chat(request(), config(wm)))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("Too many requests");
    }
}
