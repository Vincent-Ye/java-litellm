package dev.javalitellm.proxy.teams;

import java.math.BigDecimal;

public record Team(
        String teamId,
        String alias,
        BigDecimal maxBudget,
        BigDecimal spend,
        Integer tpmLimit,
        Integer rpmLimit,
        boolean blocked) {

    public boolean overBudget() {
        return maxBudget != null && spend.compareTo(maxBudget) >= 0;
    }
}
