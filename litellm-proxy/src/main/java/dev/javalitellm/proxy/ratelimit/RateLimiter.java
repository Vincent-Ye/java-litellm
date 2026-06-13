package dev.javalitellm.proxy.ratelimit;

/**
 * Per-scope sliding-window limits (scope = key hash today; team/global tiers come with the
 * hierarchy work). The in-memory implementation is exact for a single replica; a Redis-backed one
 * replaces it for multi-replica deployments.
 */
public interface RateLimiter {

    /** Counts one request against the scope; false when the rpm limit is already spent. */
    boolean tryAcquireRequest(String scope, Integer rpmLimit);

    /** True when the scope's token usage in the current window is below the tpm limit. */
    boolean withinTokenLimit(String scope, Integer tpmLimit);

    /** Records tokens consumed by a completed call. */
    void recordTokens(String scope, int tokens);
}
