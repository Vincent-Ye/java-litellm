package dev.javalitellm.router;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-local state store. Latency and usage use a one-minute sliding window. */
public final class InMemoryRouterStateStore implements RouterStateStore {

    private static final long WINDOW_MILLIS = 60_000;

    private record Sample(long atMillis, long latencyMillis, int tokens) {}

    private static final class DeploymentState {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        volatile long cooldownUntilMillis;
        final ConcurrentLinkedDeque<Sample> samples = new ConcurrentLinkedDeque<>();

        void prune(long now) {
            Sample head;
            while ((head = samples.peekFirst()) != null && now - head.atMillis() > WINDOW_MILLIS) {
                samples.pollFirst();
            }
        }
    }

    private final Map<String, DeploymentState> states = new ConcurrentHashMap<>();

    private DeploymentState state(String deploymentId) {
        return states.computeIfAbsent(deploymentId, k -> new DeploymentState());
    }

    @Override
    public void recordSuccess(String deploymentId, long latencyMillis, int totalTokens) {
        DeploymentState s = state(deploymentId);
        s.consecutiveFailures.set(0);
        long now = System.currentTimeMillis();
        s.samples.addLast(new Sample(now, latencyMillis, totalTokens));
        s.prune(now);
    }

    @Override
    public void recordFailure(String deploymentId) {
        state(deploymentId).consecutiveFailures.incrementAndGet();
    }

    @Override
    public int consecutiveFailures(String deploymentId) {
        return state(deploymentId).consecutiveFailures.get();
    }

    @Override
    public void startCooldown(String deploymentId, Duration duration) {
        state(deploymentId).cooldownUntilMillis = System.currentTimeMillis() + duration.toMillis();
    }

    @Override
    public boolean isCoolingDown(String deploymentId) {
        return state(deploymentId).cooldownUntilMillis > System.currentTimeMillis();
    }

    @Override
    public void incrementInFlight(String deploymentId) {
        state(deploymentId).inFlight.incrementAndGet();
    }

    @Override
    public void decrementInFlight(String deploymentId) {
        state(deploymentId).inFlight.decrementAndGet();
    }

    @Override
    public int inFlight(String deploymentId) {
        return state(deploymentId).inFlight.get();
    }

    @Override
    public OptionalDouble averageLatencyMillis(String deploymentId) {
        DeploymentState s = state(deploymentId);
        s.prune(System.currentTimeMillis());
        return s.samples.stream().mapToLong(Sample::latencyMillis).average();
    }

    @Override
    public int tokensLastMinute(String deploymentId) {
        DeploymentState s = state(deploymentId);
        s.prune(System.currentTimeMillis());
        return s.samples.stream().mapToInt(Sample::tokens).sum();
    }

    @Override
    public int requestsLastMinute(String deploymentId) {
        DeploymentState s = state(deploymentId);
        s.prune(System.currentTimeMillis());
        return s.samples.size();
    }
}
