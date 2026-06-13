package dev.javalitellm.provider.azure;

import dev.javalitellm.core.spi.Capability;
import dev.javalitellm.core.spi.ProviderConfig;
import dev.javalitellm.provider.openai.OpenAiCompatibleProvider;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Azure OpenAI adapter. Same wire format as OpenAI, but the model is a deployment name baked into
 * the URL path, the API version travels as a query parameter, and auth uses the {@code api-key}
 * header. {@code apiBase} (the resource endpoint, e.g. {@code https://myres.openai.azure.com}) is
 * required.
 */
public final class AzureOpenAiProvider extends OpenAiCompatibleProvider {

    public static final String DEFAULT_API_VERSION = "2024-10-21";

    @Override
    public String name() {
        return "azure";
    }

    @Override
    protected String defaultApiBase() {
        return null; // every Azure resource has its own endpoint
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(
                Capability.CHAT,
                Capability.STREAMING,
                Capability.TOOLS,
                Capability.VISION,
                Capability.EMBEDDING,
                Capability.STRUCTURED_OUTPUT);
    }

    @Override
    protected URI chatEndpoint(ProviderConfig config, String model) {
        return deploymentUri(config, model, "chat/completions");
    }

    @Override
    protected URI embeddingsEndpoint(ProviderConfig config, String model) {
        return deploymentUri(config, model, "embeddings");
    }

    @Override
    protected Map<String, String> headers(ProviderConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (config.apiKey() != null) {
            headers.put("api-key", config.apiKey());
        }
        headers.putAll(config.extraHeaders());
        return headers;
    }

    private URI deploymentUri(ProviderConfig config, String deployment, String operation) {
        String version = config.apiVersion() != null ? config.apiVersion() : DEFAULT_API_VERSION;
        return URI.create(resolvedBase(config, deployment) + "/openai/deployments/" + deployment + "/" + operation
                + "?api-version=" + version);
    }
}
