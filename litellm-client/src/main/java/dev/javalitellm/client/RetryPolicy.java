package dev.javalitellm.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter. Only exceptions flagged retryable are retried;
 * {@code maxAttempts} counts the first call.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public static final RetryPolicy NONE = new RetryPolicy(1, Duration.ZERO, Duration.ZERO);

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }

    public static RetryPolicy exponential(int maxAttempts) {
        return new RetryPolicy(maxAttempts, Duration.ofMillis(500), Duration.ofSeconds(8));
    }

    /** Delay before retry attempt {@code attempt} (1-based: 1 = first retry). */
    public Duration delayBeforeRetry(int attempt) {
        long cappedMillis = Math.min(maxDelay.toMillis(), baseDelay.toMillis() * (1L << (attempt - 1)));
        return cappedMillis <= 0
                ? Duration.ZERO
                : Duration.ofMillis(ThreadLocalRandom.current().nextLong(cappedMillis + 1));
    }
}
