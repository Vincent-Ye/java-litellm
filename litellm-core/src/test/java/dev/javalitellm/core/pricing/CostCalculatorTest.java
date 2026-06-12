package dev.javalitellm.core.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javalitellm.core.chat.Usage;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CostCalculatorTest {

    private final CostCalculator calculator = CostCalculator.bundled();

    @Test
    void bundledTableLoadsThousandsOfModels() {
        assertThat(ModelPriceTable.bundled().size()).isGreaterThan(1000);
    }

    @Test
    void computesCostForKnownOpenAiModel() {
        // gpt-4o: input 2.5e-06, output 1e-05 per token
        BigDecimal cost = calculator.cost("openai", "gpt-4o", Usage.of(1000, 100));

        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.0035"));
    }

    @Test
    void billsCachedTokensAtCacheReadRate() {
        // gpt-4o cache read: 1.25e-06 per token
        BigDecimal cost = calculator.cost("openai", "gpt-4o", new Usage(1000, 0, 400, null));

        // 600 * 2.5e-6 + 400 * 1.25e-6 = 0.0015 + 0.0005
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.002"));
    }

    @Test
    void findsProviderPrefixedEntries() {
        assertThat(ModelPriceTable.bundled().find("anthropic", "claude-sonnet-4-5"))
                .isPresent();
    }

    @Test
    void unknownModelYieldsNullNotZero() {
        assertThat(calculator.cost("openai", "totally-unknown-model", Usage.of(10, 10)))
                .isNull();
        assertThat(calculator.cost("openai", "gpt-4o", null)).isNull();
    }
}
