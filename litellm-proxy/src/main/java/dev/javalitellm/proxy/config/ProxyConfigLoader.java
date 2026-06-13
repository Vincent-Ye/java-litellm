package dev.javalitellm.proxy.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.javalitellm.core.spi.ProviderConfig;
import dev.javalitellm.router.Deployment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads a LiteLLM-style {@code config.yaml}:
 *
 * <pre>
 * model_list:
 *   - model_name: gpt-4o                  # model group exposed to clients
 *     litellm_params:
 *       model: openai/gpt-4o              # actual route
 *       api_key: os.environ/OPENAI_API_KEY
 *       api_base: https://...             # optional
 *       api_version: ...                  # optional (azure)
 *       aws_region_name: ...              # optional (bedrock)
 *       weight: 2                         # optional
 * general_settings:
 *   master_key: os.environ/LITELLM_MASTER_KEY
 * </pre>
 *
 * <p>Values of the form {@code os.environ/NAME} resolve from environment variables, matching
 * LiteLLM's convention so existing configs port over unchanged.
 */
public final class ProxyConfigLoader {

    public record LoadedConfig(
            List<Deployment> deployments, String masterKey, boolean cacheEnabled, int cacheTtlSeconds) {}

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public LoadedConfig load(Path path) {
        JsonNode root;
        try {
            root = yaml.readTree(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read config file " + path, e);
        }

        List<Deployment> deployments = new ArrayList<>();
        int index = 0;
        for (JsonNode entry : root.path("model_list")) {
            deployments.add(parseEntry(
                    entry.path("model_name").asText(),
                    entry.path("litellm_params"),
                    entry.path("model_name").asText() + "-" + index));
            index++;
        }
        if (deployments.isEmpty()) {
            throw new IllegalArgumentException("config has no model_list entries: " + path);
        }

        String masterKey = resolve(root.path("general_settings"), "master_key").orElse(null);
        JsonNode litellmSettings = root.path("litellm_settings");
        boolean cacheEnabled = litellmSettings.path("cache").asBoolean(false);
        int cacheTtl = litellmSettings.path("cache_params").path("ttl").asInt(300);
        return new LoadedConfig(deployments, masterKey, cacheEnabled, cacheTtl);
    }

    /**
     * Parses one {@code model_list} entry (a model_name + its litellm_params) into a deployment.
     * Reused by the dynamic {@code /model/new} endpoint. {@code os.environ/} refs are resolved.
     */
    public Deployment parseEntry(String group, JsonNode params, String id) {
        if (group == null || group.isBlank() || params.isMissingNode()) {
            throw new IllegalArgumentException("model entry needs model_name and litellm_params");
        }
        ProviderConfig.Builder config = ProviderConfig.builder();
        resolve(params, "api_key").ifPresent(config::apiKey);
        resolve(params, "api_base").ifPresent(config::apiBase);
        resolve(params, "api_version").ifPresent(config::apiVersion);
        resolve(params, "aws_region_name").ifPresent(config::region);
        return Deployment.builder()
                .id(id)
                .modelGroup(group)
                .model(params.path("model").asText())
                .config(config.build())
                .weight(params.path("weight").asInt(1))
                .tpm(params.has("tpm") ? params.path("tpm").asInt() : null)
                .rpm(params.has("rpm") ? params.path("rpm").asInt() : null)
                .build();
    }

    private java.util.Optional<String> resolve(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        if (value.startsWith("os.environ/")) {
            String env = System.getenv(value.substring("os.environ/".length()));
            return java.util.Optional.ofNullable(env);
        }
        return java.util.Optional.of(value);
    }
}
