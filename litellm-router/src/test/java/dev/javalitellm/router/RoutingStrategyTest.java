package dev.javalitellm.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingStrategyTest {

    private static Deployment deployment(String id, int weight) {
        return Deployment.builder()
                .id(id)
                .modelGroup("g")
                .model("openai/gpt-4o")
                .weight(weight)
                .build();
    }

    private static Deployment limited(String id, Integer tpm, Integer rpm) {
        return Deployment.builder()
                .id(id)
                .modelGroup("g")
                .model("openai/gpt-4o")
                .tpm(tpm)
                .rpm(rpm)
                .build();
    }

    @Test
    void simpleShuffleRespectsWeights() {
        var strategy = RoutingStrategy.simpleShuffle();
        var state = new InMemoryRouterStateStore();
        var heavy = deployment("heavy", 9);
        var light = deployment("light", 1);

        int heavyPicks = 0;
        for (int i = 0; i < 1000; i++) {
            if (strategy.select(List.of(heavy, light), state) == heavy) {
                heavyPicks++;
            }
        }

        assertThat(heavyPicks).isBetween(800, 980); // expectation 900, generous tolerance
    }

    @Test
    void leastBusyPicksLowestInFlight() {
        var state = new InMemoryRouterStateStore();
        var a = deployment("a", 1);
        var b = deployment("b", 1);
        state.incrementInFlight("a");
        state.incrementInFlight("a");
        state.incrementInFlight("b");

        assertThat(RoutingStrategy.leastBusy().select(List.of(a, b), state)).isEqualTo(b);
    }

    @Test
    void latencyBasedPrefersUntriedThenFastest() {
        var state = new InMemoryRouterStateStore();
        var fast = deployment("fast", 1);
        var slow = deployment("slow", 1);
        var fresh = deployment("fresh", 1);
        state.recordSuccess("fast", 100, 10);
        state.recordSuccess("slow", 900, 10);

        assertThat(RoutingStrategy.latencyBased().select(List.of(fast, slow, fresh), state))
                .isEqualTo(fresh);
        assertThat(RoutingStrategy.latencyBased().select(List.of(fast, slow), state))
                .isEqualTo(fast);
    }

    @Test
    void usageBasedPicksMostHeadroom() {
        var state = new InMemoryRouterStateStore();
        var nearLimit = limited("nearLimit", 1000, null);
        var free = limited("free", 1000, null);
        state.recordSuccess("nearLimit", 50, 900);
        state.recordSuccess("free", 50, 100);

        assertThat(RoutingStrategy.usageBased().select(List.of(nearLimit, free), state))
                .isEqualTo(free);
    }

    @Test
    void cooldownExpires() throws InterruptedException {
        var state = new InMemoryRouterStateStore();
        state.startCooldown("a", Duration.ofMillis(50));

        assertThat(state.isCoolingDown("a")).isTrue();
        Thread.sleep(80);
        assertThat(state.isCoolingDown("a")).isFalse();
    }
}
