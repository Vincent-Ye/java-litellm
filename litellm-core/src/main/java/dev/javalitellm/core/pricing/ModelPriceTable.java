package dev.javalitellm.core.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Per-token prices keyed by model name, loaded from LiteLLM's community-maintained
 * {@code model_prices_and_context_window.json}. A snapshot is bundled as a classpath resource;
 * {@link #load(InputStream)} accepts a fresher copy (remote refresh is wired up at the proxy layer).
 */
public final class ModelPriceTable {

    /** Prices are per token, in USD. {@code cacheReadCostPerToken} may be null. */
    public record ModelPrice(
            BigDecimal inputCostPerToken, BigDecimal outputCostPerToken, BigDecimal cacheReadCostPerToken) {}

    private static final String BUNDLED_RESOURCE = "/model_prices_and_context_window.json";

    private static final class Bundled {
        static final ModelPriceTable INSTANCE = loadBundled();
    }

    private final Map<String, ModelPrice> prices;

    private ModelPriceTable(Map<String, ModelPrice> prices) {
        this.prices = prices;
    }

    public static ModelPriceTable bundled() {
        return Bundled.INSTANCE;
    }

    public static ModelPriceTable load(InputStream in) throws IOException {
        JsonNode root = new ObjectMapper().readTree(in);
        Map<String, ModelPrice> prices = new HashMap<>();
        root.properties().forEach(entry -> {
            JsonNode node = entry.getValue();
            if ("sample_spec".equals(entry.getKey()) || !node.has("input_cost_per_token")) {
                return;
            }
            prices.put(
                    entry.getKey(),
                    new ModelPrice(
                            BigDecimal.valueOf(node.path("input_cost_per_token").asDouble()),
                            BigDecimal.valueOf(
                                    node.path("output_cost_per_token").asDouble()),
                            node.hasNonNull("cache_read_input_token_cost")
                                    ? BigDecimal.valueOf(node.path("cache_read_input_token_cost")
                                            .asDouble())
                                    : null));
        });
        return new ModelPriceTable(Map.copyOf(prices));
    }

    /**
     * Looks up by the table's key conventions: first {@code "provider/model"}, then the bare model
     * name. {@code model} may be a response-reported name like {@code "gpt-4o-2024-08-06"}.
     */
    public Optional<ModelPrice> find(String provider, String model) {
        if (model == null) {
            return Optional.empty();
        }
        ModelPrice price = provider == null ? null : prices.get(provider + "/" + model);
        if (price == null) {
            price = prices.get(model);
        }
        return Optional.ofNullable(price);
    }

    public int size() {
        return prices.size();
    }

    private static ModelPriceTable loadBundled() {
        try (InputStream in = ModelPriceTable.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("bundled price table resource missing: " + BUNDLED_RESOURCE);
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load bundled price table", e);
        }
    }
}
