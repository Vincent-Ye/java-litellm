package dev.javalitellm.router;

import dev.javalitellm.client.LiteLlm;
import dev.javalitellm.client.RetryPolicy;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.exception.ApiTimeoutException;
import dev.javalitellm.core.exception.AuthenticationException;
import dev.javalitellm.core.exception.ContextWindowExceededException;
import dev.javalitellm.core.exception.LiteLlmException;
import dev.javalitellm.core.exception.NotFoundException;
import dev.javalitellm.core.spi.StreamHandler;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Load balancer over deployments grouped by model group. A request addressed to a group is routed
 * by the configured strategy across healthy (non-cooling-down) deployments; failures walk the
 * group, then the fallback chain, inside one total deadline.
 *
 * <p>Reliability semantics: retryable errors and auth errors move to the next deployment; a 429
 * cools the deployment down immediately, other failures after {@code failureThreshold} consecutive
 * errors. {@link ContextWindowExceededException} jumps to the group's context-window fallbacks.
 * Other non-retryable errors fail fast — no other deployment would behave differently.
 */
public final class Router {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    private final Map<String, List<Deployment>> groups;
    private final RoutingStrategy strategy;
    private final RouterStateStore state;
    private final LiteLlm client;
    private final Map<String, List<String>> fallbacks;
    private final Map<String, List<String>> contextWindowFallbacks;
    private final Duration cooldown;
    private final int failureThreshold;
    private final Duration totalTimeout;

    private Router(Builder builder) {
        Map<String, List<Deployment>> grouped = new LinkedHashMap<>();
        for (Deployment deployment : builder.deployments) {
            grouped.computeIfAbsent(deployment.modelGroup(), k -> new ArrayList<>())
                    .add(deployment);
        }
        grouped.replaceAll((k, v) -> List.copyOf(v));
        this.groups = Map.copyOf(grouped);
        this.strategy = builder.strategy;
        this.state = builder.state;
        this.client = builder.client != null
                ? builder.client
                : LiteLlm.builder().retryPolicy(RetryPolicy.NONE).build();
        this.fallbacks = Map.copyOf(builder.fallbacks);
        this.contextWindowFallbacks = Map.copyOf(builder.contextWindowFallbacks);
        this.cooldown = builder.cooldown;
        this.failureThreshold = builder.failureThreshold;
        this.totalTimeout = builder.totalTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    public RouterStateStore state() {
        return state;
    }

    /** Model groups known to this router, in configuration order. */
    public Set<String> modelGroups() {
        return groups.keySet();
    }

    /**
     * Picks one healthy deployment of the group without calling it — for operations the router does
     * not orchestrate itself yet (e.g. embeddings at the proxy layer). Null when none is available.
     */
    public Deployment pick(String group) {
        return selectHealthy(group, Set.of());
    }

    public ChatResponse chat(ChatRequest request) {
        return route(request, null);
    }

    /**
     * Streaming with the same routing semantics; a deployment failure before the first chunk moves
     * on to the next candidate, after the first chunk the error is surfaced as-is.
     */
    public void chatStream(ChatRequest request, StreamHandler handler) {
        route(request, handler);
    }

    private ChatResponse route(ChatRequest request, StreamHandler streamHandler) {
        String primaryGroup = request.model();
        if (!groups.containsKey(primaryGroup)) {
            throw new NotFoundException("unknown model group '" + primaryGroup + "'", null, primaryGroup);
        }
        long deadlineNanos = System.nanoTime() + totalTimeout.toNanos();

        Deque<String> groupQueue = new ArrayDeque<>();
        groupQueue.add(primaryGroup);
        fallbacks.getOrDefault(primaryGroup, List.of()).forEach(groupQueue::addLast);
        Set<String> visitedGroups = new HashSet<>();
        LiteLlmException last = null;

        while (!groupQueue.isEmpty()) {
            String group = groupQueue.pollFirst();
            if (!visitedGroups.add(group) || !groups.containsKey(group)) {
                continue;
            }

            Set<String> tried = new HashSet<>();
            while (true) {
                checkDeadline(deadlineNanos, group, last);
                Deployment deployment = selectHealthy(group, tried);
                if (deployment == null) {
                    break; // group exhausted, move to next group in the chain
                }
                tried.add(deployment.id());
                try {
                    return attempt(deployment, request, streamHandler);
                } catch (LiteLlmException e) {
                    last = e;
                    recordFailure(deployment, e);
                    if (e instanceof ContextWindowExceededException) {
                        List<String> contextChain = contextWindowFallbacks.get(group);
                        if (contextChain == null || contextChain.isEmpty()) {
                            throw e;
                        }
                        // Jump the chain: context fallbacks take priority over generic ones.
                        for (int i = contextChain.size() - 1; i >= 0; i--) {
                            groupQueue.addFirst(contextChain.get(i));
                        }
                        break;
                    }
                    if (!e.retryable() && !(e instanceof AuthenticationException)) {
                        throw e; // no other deployment would behave differently
                    }
                    log.debug("deployment {} failed ({}), trying next", deployment.id(), e.getMessage());
                }
            }
        }
        throw last != null
                ? last
                : new NotFoundException("no deployment available for group '" + primaryGroup + "'", null, primaryGroup);
    }

    private Deployment selectHealthy(String group, Set<String> tried) {
        List<Deployment> healthy = groups.getOrDefault(group, List.of()).stream()
                .filter(d -> !tried.contains(d.id()))
                .filter(d -> !state.isCoolingDown(d.id()))
                .toList();
        return healthy.isEmpty() ? null : strategy.select(healthy, state);
    }

    private ChatResponse attempt(Deployment deployment, ChatRequest request, StreamHandler streamHandler) {
        ChatRequest routed = request.toBuilder().model(deployment.model()).build();
        state.incrementInFlight(deployment.id());
        long startNanos = System.nanoTime();
        try {
            if (streamHandler == null) {
                ChatResponse response = client.chat(routed, deployment.config());
                recordSuccess(deployment, startNanos, response);
                return response;
            }
            streamAttempt(deployment, routed, streamHandler, startNanos);
            return null;
        } finally {
            state.decrementInFlight(deployment.id());
        }
    }

    /** Converts pre-first-chunk stream errors back into exceptions so the routing loop can retry. */
    private void streamAttempt(Deployment deployment, ChatRequest routed, StreamHandler handler, long startNanos) {
        AtomicBoolean delivered = new AtomicBoolean(false);
        LiteLlmException[] preChunkError = new LiteLlmException[1];
        client.chatStream(routed, deployment.config(), new StreamHandler() {
            @Override
            public void onChunk(ChatChunk chunk) {
                delivered.set(true);
                handler.onChunk(chunk);
            }

            @Override
            public void onComplete() {
                recordSuccess(deployment, startNanos, null);
                handler.onComplete();
            }

            @Override
            public void onError(LiteLlmException e) {
                if (delivered.get()) {
                    handler.onError(e); // mid-stream: cannot transparently retry
                } else {
                    preChunkError[0] = e;
                }
            }
        });
        if (preChunkError[0] != null) {
            throw preChunkError[0];
        }
    }

    private void recordSuccess(Deployment deployment, long startNanos, ChatResponse response) {
        long latencyMillis = (System.nanoTime() - startNanos) / 1_000_000;
        int tokens =
                response != null && response.usage() != null ? response.usage().totalTokens() : 0;
        state.recordSuccess(deployment.id(), latencyMillis, tokens);
    }

    private void recordFailure(Deployment deployment, LiteLlmException e) {
        state.recordFailure(deployment.id());
        if (e.statusCode() == 429 || state.consecutiveFailures(deployment.id()) >= failureThreshold) {
            log.info("cooling down deployment {} for {} after {}", deployment.id(), cooldown, e.getMessage());
            state.startCooldown(deployment.id(), cooldown);
        }
    }

    private void checkDeadline(long deadlineNanos, String group, LiteLlmException last) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new ApiTimeoutException(
                    "router exhausted its " + totalTimeout + " budget on group '" + group + "'", null, group, last);
        }
    }

    public static final class Builder {
        private final List<Deployment> deployments = new ArrayList<>();
        private final Map<String, List<String>> fallbacks = new LinkedHashMap<>();
        private final Map<String, List<String>> contextWindowFallbacks = new LinkedHashMap<>();
        private RoutingStrategy strategy = RoutingStrategy.simpleShuffle();
        private RouterStateStore state = new InMemoryRouterStateStore();
        private LiteLlm client;
        private Duration cooldown = Duration.ofSeconds(5);
        private int failureThreshold = 3;
        private Duration totalTimeout = Duration.ofMinutes(10);

        public Builder deployment(Deployment deployment) {
            this.deployments.add(deployment);
            return this;
        }

        public Builder deployments(List<Deployment> deployments) {
            this.deployments.addAll(deployments);
            return this;
        }

        public Builder strategy(RoutingStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder state(RouterStateStore state) {
            this.state = state;
            return this;
        }

        /** Client used for the actual calls; defaults to one with client-level retries disabled. */
        public Builder client(LiteLlm client) {
            this.client = client;
            return this;
        }

        public Builder fallback(String group, List<String> fallbackGroups) {
            this.fallbacks.put(group, List.copyOf(fallbackGroups));
            return this;
        }

        public Builder contextWindowFallback(String group, List<String> fallbackGroups) {
            this.contextWindowFallbacks.put(group, List.copyOf(fallbackGroups));
            return this;
        }

        public Builder cooldown(Duration cooldown) {
            this.cooldown = cooldown;
            return this;
        }

        /** Consecutive failures before a deployment is cooled down (429 cools down immediately). */
        public Builder failureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
            return this;
        }

        public Builder totalTimeout(Duration totalTimeout) {
            this.totalTimeout = totalTimeout;
            return this;
        }

        public Router build() {
            if (deployments.isEmpty()) {
                throw new IllegalArgumentException("router needs at least one deployment");
            }
            return new Router(this);
        }
    }
}
