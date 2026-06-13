package dev.javalitellm.provider.gemini;

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
import dev.javalitellm.core.embedding.EmbeddingRequest;
import dev.javalitellm.core.embedding.EmbeddingResponse;
import dev.javalitellm.core.exception.RateLimitException;
import dev.javalitellm.core.spi.ProviderConfig;
import dev.javalitellm.core.spi.StreamHandler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@WireMockTest
class GeminiProviderWireMockTest {

    private final GeminiProvider provider = new GeminiProvider();

    private static ChatRequest request() {
        return ChatRequest.builder()
                .model("gemini-2.0-flash")
                .message(Message.user("hi"))
                .build();
    }

    private static ProviderConfig config(WireMockRuntimeInfo wm) {
        return ProviderConfig.builder()
                .apiKey("gemini-key")
                .apiBase(wm.getHttpBaseUrl())
                .build();
    }

    @Test
    void chatUsesGoogApiKeyHeaderAndModelPath(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(
                        post(urlEqualTo("/models/gemini-2.0-flash:generateContent"))
                                .withHeader("x-goog-api-key", equalTo("gemini-key"))
                                .willReturn(
                                        aResponse()
                                                .withStatus(200)
                                                .withBody(
                                                        """
                                        {"candidates":[{"content":{"parts":[{"text":"hello"}],"role":"model"},
                                                        "finishReason":"STOP"}],
                                         "usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2},
                                         "modelVersion":"gemini-2.0-flash"}
                                        """)));

        ChatResponse resp = provider.chat(request(), config(wm));

        assertThat(resp.firstText()).isEqualTo("hello");
        assertThat(resp.usage().totalTokens()).isEqualTo(6);
    }

    @Test
    void chatStreamUsesAltSseAndAggregatesParts(WireMockRuntimeInfo wm) {
        String sse =
                """
                data: {"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"}}],"modelVersion":"gemini-2.0-flash"}

                data: {"candidates":[{"content":{"parts":[{"text":"lo"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2},"modelVersion":"gemini-2.0-flash"}

                """;
        wm.getWireMock()
                .register(post(urlEqualTo("/models/gemini-2.0-flash:streamGenerateContent?alt=sse"))
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

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).textDelta()).isEqualTo("Hel");
        assertThat(chunks.get(1).textDelta()).isEqualTo("lo");
        assertThat(chunks.get(1).finishReason()).isEqualTo("stop");
        assertThat(chunks.get(1).usage().promptTokens()).isEqualTo(4);
    }

    @Test
    void embeddingUsesBatchEndpoint(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(post(urlEqualTo("/models/text-embedding-004:batchEmbedContents"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withBody("{\"embeddings\":[{\"values\":[0.1,0.2]},{\"values\":[0.3,0.4]}]}")));

        EmbeddingResponse resp =
                provider.embedding(EmbeddingRequest.of("text-embedding-004", List.of("a", "b")), config(wm));

        assertThat(resp.embeddings()).hasSize(2);
        assertThat(resp.embeddings().getFirst()).containsExactly(0.1f, 0.2f);
    }

    @Test
    void mapsGoogleErrorFormat(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(post(urlEqualTo("/models/gemini-2.0-flash:generateContent"))
                        .willReturn(aResponse()
                                .withStatus(429)
                                .withBody("{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted\","
                                        + "\"status\":\"RESOURCE_EXHAUSTED\"}}")));

        assertThatThrownBy(() -> provider.chat(request(), config(wm)))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("Resource has been exhausted");
    }
}
