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
                    litellm_settings:
                      cache: true
                      cache_params:
                        ttl: 60
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
    void cacheServesIdenticalRequests() throws IOException {
        String body = "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"cache me uniquely\"}]}";
        int upstreamBefore = UPSTREAM.getAllServeEvents().size();

        ResponseEntity<String> first =
                rest.postForEntity("/v1/chat/completions", new HttpEntity<>(body, headers(MASTER_KEY)), String.class);
        ResponseEntity<String> second =
                rest.postForEntity("/v1/chat/completions", new HttpEntity<>(body, headers(MASTER_KEY)), String.class);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getHeaders().getFirst("x-litellm-cache")).isEqualTo("hit");
        assertThat(mapper.readTree(second.getBody())
                        .path("choices")
                        .get(0)
                        .path("message")
                        .path("content"))
                .isEqualTo(mapper.readTree(first.getBody())
                        .path("choices")
                        .get(0)
                        .path("message")
                        .path("content"));
        assertThat(UPSTREAM.getAllServeEvents().size()).isEqualTo(upstreamBefore + 1);
    }

    @Test
    void enforcesRpmLimit() throws IOException {
        String key = generateKey("{\"key_alias\":\"rpm-test\",\"rpm_limit\":1}");
        // distinct bodies so the cache cannot absorb the second request
        ResponseEntity<String> first = rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"rpm one\"}]}",
                        headers(key)),
                String.class);
        ResponseEntity<String> second = rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"rpm two\"}]}",
                        headers(key)),
                String.class);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(429);
        assertThat(second.getBody()).contains("RPM");
    }

    @Test
    void exposesPrometheusMetrics() {
        rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"metrics probe\"}]}",
                        headers(MASTER_KEY)),
                String.class);

        ResponseEntity<String> metrics = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(metrics.getStatusCode().value()).isEqualTo(200);
        assertThat(metrics.getBody()).contains("litellm_request_duration");
        assertThat(metrics.getBody()).contains("litellm_tokens_total");
    }

    @Test
    void spendEndpointsAreMasterOnly() throws IOException {
        String key = generateKey("{}");

        ResponseEntity<String> denied = rest.exchange(
                "/spend/logs", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers(key)), String.class);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> logs = rest.exchange(
                "/spend/logs",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers(MASTER_KEY)),
                String.class);
        assertThat(logs.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(logs.getBody()).isArray()).isTrue();

        ResponseEntity<String> byKey = rest.exchange(
                "/spend/keys",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers(MASTER_KEY)),
                String.class);
        assertThat(byKey.getStatusCode().value()).isEqualTo(200);
    }

    private String createTeam(String body) throws IOException {
        ResponseEntity<String> response =
                rest.postForEntity("/team/new", new HttpEntity<>(body, headers(MASTER_KEY)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return mapper.readTree(response.getBody()).path("team_id").asText();
    }

    @Test
    void teamManagementIsMasterOnlyAndCascadesSpend() throws IOException {
        String key = generateKey("{}");
        ResponseEntity<String> denied =
                rest.postForEntity("/team/new", new HttpEntity<>("{}", headers(key)), String.class);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);

        String teamId = createTeam("{\"team_alias\":\"acme\",\"max_budget\":100.0}");
        String teamKey = generateKey("{\"key_alias\":\"acme-key\",\"team_id\":\"" + teamId + "\"}");

        rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"team spend probe\"}]}",
                        headers(teamKey)),
                String.class);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            BigDecimal teamSpend =
                    jdbc.queryForObject("SELECT spend FROM teams WHERE team_id = ?", BigDecimal.class, teamId);
            assertThat(teamSpend).isGreaterThan(BigDecimal.ZERO);
        });
    }

    @Test
    void teamBudgetBlocksAcrossKeys() throws IOException {
        String teamId = createTeam("{\"team_alias\":\"frugal\",\"max_budget\":0.0000001}");
        jdbc.update("UPDATE teams SET spend = 5.0 WHERE team_id = ?", teamId);
        String teamKey = generateKey("{\"team_id\":\"" + teamId + "\"}");

        ResponseEntity<String> response = rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"over team budget\"}]}",
                        headers(teamKey)),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).contains("team");
    }

    @Test
    void teamRpmLimitSharedAcrossKeys() throws IOException {
        String teamId = createTeam("{\"team_alias\":\"throttled\",\"rpm_limit\":1}");
        String keyA = generateKey("{\"team_id\":\"" + teamId + "\"}");
        String keyB = generateKey("{\"team_id\":\"" + teamId + "\"}");

        ResponseEntity<String> first = rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"team rpm one\"}]}",
                        headers(keyA)),
                String.class);
        ResponseEntity<String> second = rest.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"team rpm two\"}]}",
                        headers(keyB)),
                String.class);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(429);
        assertThat(second.getBody()).contains("team");
    }

    @Test
    void userCreationAndInfo() throws IOException {
        ResponseEntity<String> created = rest.postForEntity(
                "/user/new",
                new HttpEntity<>("{\"user_alias\":\"alice\",\"max_budget\":50.0}", headers(MASTER_KEY)),
                String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        String userId = mapper.readTree(created.getBody()).path("user_id").asText();
        assertThat(userId).startsWith("user-");

        ResponseEntity<String> info = rest.postForEntity(
                "/user/info", new HttpEntity<>("{\"user_id\":\"" + userId + "\"}", headers(MASTER_KEY)), String.class);
        assertThat(mapper.readTree(info.getBody()).path("user_alias").asText()).isEqualTo("alice");
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
