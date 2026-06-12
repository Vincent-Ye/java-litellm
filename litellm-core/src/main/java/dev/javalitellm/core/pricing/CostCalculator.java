package dev.javalitellm.core.pricing;

import dev.javalitellm.core.chat.Usage;
import java.math.BigDecimal;

/**
 * Computes call cost from usage and the price table, matching LiteLLM's formula: cached prompt
 * tokens are billed at the cache-read rate when the model defines one, the rest at the input rate.
 */
public final class CostCalculator {

    private final ModelPriceTable table;

    public CostCalculator(ModelPriceTable table) {
        this.table = table;
    }

    public static CostCalculator bundled() {
        return new CostCalculator(ModelPriceTable.bundled());
    }

    /** Returns null when usage is missing or the model is not in the price table. */
    public BigDecimal cost(String provider, String model, Usage usage) {
        if (usage == null) {
            return null;
        }
        return table.find(provider, model)
                .map(price -> {
                    int cached = usage.cachedTokens() != null && price.cacheReadCostPerToken() != null
                            ? usage.cachedTokens()
                            : 0;
                    BigDecimal cost =
                            price.inputCostPerToken().multiply(BigDecimal.valueOf(usage.promptTokens() - cached));
                    if (cached > 0) {
                        cost = cost.add(price.cacheReadCostPerToken().multiply(BigDecimal.valueOf(cached)));
                    }
                    return cost.add(price.outputCostPerToken().multiply(BigDecimal.valueOf(usage.completionTokens())));
                })
                .orElse(null);
    }
}
