package dev.javalitellm.core.http;

import dev.javalitellm.core.exception.ApiTimeoutException;
import dev.javalitellm.core.exception.LiteLlmException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Thin JDK HttpClient wrapper shared by provider adapters. Maps transport-level failures to the
 * unified exception hierarchy; HTTP-status error mapping stays with the provider (bodies are
 * provider-specific).
 */
public final class HttpTransport {

    public record Response(int status, String body) {}

    private static final String DONE_MARKER = "[DONE]";

    private final HttpClient client;

    public HttpTransport() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Response postJson(
            URI uri, Map<String, String> headers, String body, Duration timeout, String provider, String model) {
        HttpRequest request = buildRequest(uri, headers, body, timeout);
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (HttpTimeoutException e) {
            throw new ApiTimeoutException("request to " + uri + " timed out", provider, model, e);
        } catch (IOException e) {
            throw new LiteLlmException("I/O error calling " + uri + ": " + e.getMessage(), provider, model, 0, true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LiteLlmException("interrupted calling " + uri, provider, model, 0, false, e);
        }
    }

    /**
     * POSTs and consumes the response as an SSE stream, invoking {@code onData} once per
     * {@code data:} payload (the {@code [DONE]} sentinel is swallowed). On a non-2xx status the
     * full body is returned so the provider can map the error; otherwise returns null.
     */
    public Response postSse(
            URI uri,
            Map<String, String> headers,
            String body,
            Duration timeout,
            String provider,
            String model,
            Consumer<String> onData) {
        HttpRequest request = buildRequest(uri, headers, body, timeout);
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                try (InputStream in = response.body()) {
                    return new Response(response.statusCode(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring("data:".length()).trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if (DONE_MARKER.equals(payload)) {
                        break;
                    }
                    onData.accept(payload);
                }
            }
            return null;
        } catch (HttpTimeoutException e) {
            throw new ApiTimeoutException("request to " + uri + " timed out", provider, model, e);
        } catch (IOException e) {
            throw new LiteLlmException("I/O error calling " + uri + ": " + e.getMessage(), provider, model, 0, true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LiteLlmException("interrupted calling " + uri, provider, model, 0, false, e);
        }
    }

    private static HttpRequest buildRequest(URI uri, Map<String, String> headers, String body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return builder.build();
    }
}
