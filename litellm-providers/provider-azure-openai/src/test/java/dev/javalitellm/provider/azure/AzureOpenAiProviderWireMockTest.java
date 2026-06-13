package dev.javalitellm.provider.azure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.exception.BadRequestException;
import dev.javalitellm.core.spi.ProviderConfig;
import org.junit.jupiter.api.Test;

@WireMockTest
class AzureOpenAiProviderWireMockTest {

    private final AzureOpenAiProvider provider = new AzureOpenAiProvider();

    private static ChatRequest request() {
        // For Azure the "model" is the deployment name
        return ChatRequest.builder()
                .model("my-gpt4o-deployment")
                .message(Message.user("hi"))
                .build();
    }

    @Test
    void usesDeploymentPathApiVersionAndApiKeyHeader(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(
                        post(urlEqualTo(
                                        "/openai/deployments/my-gpt4o-deployment/chat/completions?api-version=2024-10-21"))
                                .withHeader("api-key", equalTo("azure-key"))
                                .willReturn(
                                        aResponse()
                                                .withStatus(200)
                                                .withBody(
                                                        """
                                        {"id":"chatcmpl-1","model":"gpt-4o","created":1,
                                         "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},
                                                     "finish_reason":"stop"}],
                                         "usage":{"prompt_tokens":3,"completion_tokens":2}}
                                        """)));

        ChatResponse resp = provider.chat(
                request(),
                ProviderConfig.builder()
                        .apiKey("azure-key")
                        .apiBase(wm.getHttpBaseUrl())
                        .build());

        assertThat(resp.firstText()).isEqualTo("hello");
    }

    @Test
    void honorsExplicitApiVersion(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(
                        post(urlEqualTo(
                                        "/openai/deployments/my-gpt4o-deployment/chat/completions?api-version=2025-01-01"))
                                .willReturn(
                                        aResponse()
                                                .withStatus(200)
                                                .withBody(
                                                        """
                                        {"id":"c","model":"gpt-4o","created":1,
                                         "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
                                                     "finish_reason":"stop"}]}
                                        """)));

        ChatResponse resp = provider.chat(
                request(),
                ProviderConfig.builder()
                        .apiBase(wm.getHttpBaseUrl())
                        .apiVersion("2025-01-01")
                        .build());

        assertThat(resp.firstText()).isEqualTo("ok");
    }

    @Test
    void requiresApiBase() {
        assertThatThrownBy(
                        () -> provider.chat(request(), ProviderConfig.builder().build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("requires an explicit apiBase");
    }
}
