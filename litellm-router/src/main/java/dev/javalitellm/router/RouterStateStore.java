package dev.javalitellm.router;

import java.time.Duration;
import java.util.OptionalDouble;

/**
 * Mutable routing state shared by strategies and the cooldown machinery. The in-process
 * implementation backs single-instance SDK use; a Redis-backed one (for multi-replica proxy
 * deployments) lands with the proxy milestone.
 *
 * <p>Implementations must be thread-safe.
 */
public interface RouterStateStore {

    void recordSuccess(String deploymentId, long latencyMillis, int totalTokens);

    void recordFailure(String deploymentId);

    int consecutiveFailures(String deploymentId);

    void startCooldown(String deploymentId, Duration duration);

    boolean isCoolingDown(String deploymentId);

    void incrementInFlight(String deploymentId);

    void decrementInFlight(String deploymentId);

    int inFlight(String deploymentId);

    /** Mean latency over the recent window; empty when no successful call has been recorded. */
    OptionalDouble averageLatencyMillis(String deploymentId);

    int tokensLastMinute(String deploymentId);

    int requestsLastMinute(String deploymentId);
}
