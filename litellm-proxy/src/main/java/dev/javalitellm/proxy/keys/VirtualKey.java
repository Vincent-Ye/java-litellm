package dev.javalitellm.proxy.keys;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A virtual key row. {@code models} empty means all model groups are allowed. */
public record VirtualKey(
        String tokenHash,
        String alias,
        List<String> models,
        BigDecimal maxBudget,
        BigDecimal spend,
        Instant expiresAt,
        boolean blocked,
        Integer tpmLimit,
        Integer rpmLimit,
        String teamId,
        String userId) {

    public boolean allowsModel(String modelGroup) {
        return models.isEmpty() || models.contains(modelGroup);
    }

    public boolean expired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    public boolean overBudget() {
        return maxBudget != null && spend.compareTo(maxBudget) >= 0;
    }
}
