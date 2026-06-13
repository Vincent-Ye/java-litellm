package dev.javalitellm.router;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Picks one deployment from the healthy candidates; null means the group is exhausted. */
@FunctionalInterface
public interface RoutingStrategy {

    Deployment select(List<Deployment> candidates, RouterStateStore state);

    /** Weighted random — LiteLLM's default ("simple-shuffle"). */
    static RoutingStrategy simpleShuffle() {
        return (candidates, state) -> {
            if (candidates.isEmpty()) {
                return null;
            }
            int totalWeight = candidates.stream().mapToInt(Deployment::weight).sum();
            int pick = ThreadLocalRandom.current().nextInt(totalWeight);
            for (Deployment candidate : candidates) {
                pick -= candidate.weight();
                if (pick < 0) {
                    return candidate;
                }
            }
            return candidates.getLast();
        };
    }

    /** Fewest in-flight requests wins. */
    static RoutingStrategy leastBusy() {
        return (candidates, state) -> candidates.stream()
                .min(Comparator.comparingInt(d -> state.inFlight(d.id())))
                .orElse(null);
    }

    /** Lowest mean latency wins; deployments without data yet are tried first (exploration). */
    static RoutingStrategy latencyBased() {
        return (candidates, state) -> candidates.stream()
                .min(Comparator.comparingDouble(
                        d -> state.averageLatencyMillis(d.id()).orElse(-1)))
                .orElse(null);
    }

    /** Largest remaining TPM/RPM headroom wins; deployments without limits count as fully free. */
    static RoutingStrategy usageBased() {
        return (candidates, state) -> candidates.stream()
                .max(Comparator.comparingDouble(d -> headroom(d, state)))
                .orElse(null);
    }

    private static double headroom(Deployment d, RouterStateStore state) {
        double tpmHeadroom =
                d.tpm() == null ? 1.0 : Math.max(0, d.tpm() - state.tokensLastMinute(d.id())) / (double) d.tpm();
        double rpmHeadroom =
                d.rpm() == null ? 1.0 : Math.max(0, d.rpm() - state.requestsLastMinute(d.id())) / (double) d.rpm();
        return Math.min(tpmHeadroom, rpmHeadroom);
    }
}
