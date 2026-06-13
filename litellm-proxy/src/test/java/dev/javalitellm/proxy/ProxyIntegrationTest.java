package dev.javalitellm.proxy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxyIntegrationTest {

    private static final String MASTER_KEY = "sk-master-test";
    private static final WireMockServer UPSTREAM =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void startUpstream() {
        UPSTREAM.start();
        UPSTREAM.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {"id":"chatcmpl-up1","model":"gpt-4o","created":1,
                                 "choices":[{"index":0,"message":{"role":"assistant","content":"hello from upstream"},
                                             "finish_reason":"stop"}],
                                 "usage":{"prompt_tokens":10,"completion_tokens":5}}
                                """)));
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("litellm.config", () -> writeConfig().toString());
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:proxydb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    private static Path writeConfig() {
        try {
            Path config = Files.createTempFile("litellm-proxy-test", ".yaml");
            Files.writeString(
                    config,
                    """
                    model_list:
                      - model_name: gpt-4o
                        litellm_params:
                          model: openai/gpt-4o
                          api_key: sk-upstream
                          api_base: %s/v1
                      - model_name: restricted-model
                        litellm_params:
                          model: openai/gpt-4o-mini
                          api_key: sk-upstream
                          api_base: %s/v1
                    general_settings:
                      master_key: %s
                    """
                            .formatted(UPSTREAM.baseUrl(), UPSTREAM.baseUrl(), MASTER_KEY));
            return config;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private String generateKey(String body) throws IOException {
        ResponseEntity<String> response =
                rest.postForEntity("/key/generate", new HttpEntity<>(body, headers(MASTER_KEY)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return mapper.readTree(response.getBody()).path("key").asText();
    }

    @Test
    void openAiClientFlowWithVirtualKey() throws IOException {
        String key = generateKey("{\"key_alias\":\"it-test\"}");

        ResponseEntity<String> chat = rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", headers(key)),
                String.class);

        assertThat(chat.getStatusCode().value()).isEqualTo(200);
        JsonNode body = mapper.readTree(chat.getBody());
        assertThat(body.path("object").asText()).isEqualTo("chat.completion");
        assertThat(body.path("choices").get(0).path("message").path("content").asText())
                .isEqualTo("hello from upstream");
        assertThat(body.path("usage").path("total_tokens").asInt()).isEqualTo(15);

        // spend accounting lands asynchronously
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Integer logs = jdbc.queryForObject("SELECT count(*) FROM spend_logs", Integer.class);
            assertThat(logs).isGreaterThanOrEqualTo(1);
            BigDecimal spend =
                    jdbc.queryForObject("SELECT spend FROM virtual_keys WHERE key_alias = 'it-test'", BigDecimal.class);
            assertThat(spend).isGreaterThan(BigDecimal.ZERO); // gpt-4o is in the bundled price table
        });
    }

    @Test
    void rejectsMissingAndInvalidKeys() {
        ResponseEntity<String> noAuth = rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                        new HttpHeaders() {
                            {
                                setContentType(MediaType.APPLICATION_JSON);
                            }
                        }),
                String.class);
        assertThat(noAuth.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<String> badKey = rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                        headers("sk-nonexistent")),
                String.class);
        assertThat(badKey.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void enforcesModelWhitelist() throws IOException {
        String key = generateKey("{\"models\":[\"restricted-model\"]}");

        ResponseEntity<String> forbidden = rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", headers(key)),
                String.class);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> models = rest.exchange(
                "/v1/models", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers(key)), String.class);
        JsonNode data = mapper.readTree(models.getBody()).path("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("id").asText()).isEqualTo("restricted-model");
    }

    @Test
    void enforcesBudget() throws IOException {
        String key = generateKey("{\"key_alias\":\"budget-test\",\"max_budget\":0.000001}");
        jdbc.update("UPDATE virtual_keys SET spend = 1.0 WHERE key_alias = 'budget-test'");

        ResponseEntity<String> response = rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", headers(key)),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).contains("budget");
    }

    @Test
    void keyManagementRequiresMasterKey() throws IOException {
        String key = generateKey("{}");

        ResponseEntity<String> denied =
                rest.postForEntity("/key/generate", new HttpEntity<>("{}", headers(key)), String.class);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> deleted = rest.postForEntity(
                "/key/delete", new HttpEntity<>("{\"key\":\"" + key + "\"}", headers(MASTER_KEY)), String.class);
        assertThat(deleted.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> afterDelete = rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", headers(key)),
                String.class);
        assertThat(afterDelete.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void streamsServerSentEvents() {
        // more specific stub (body match) wins over the JSON one for streaming requests
        UPSTREAM.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                                "$.stream", com.github.tomakehurst.wiremock.client.WireMock.equalTo("true")))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "text/event-stream")
                                        .withBody(
                                                """
                                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Hey"},"finish_reason":"stop"}]}

                                data: [DONE]

                                """)));
        ResponseEntity<String> response = rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}",
                        headers(MASTER_KEY)),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/event-stream");
        assertThat(response.getBody()).contains("chat.completion.chunk");
        assertThat(response.getBody()).contains("data: [DONE]");
    }
}
