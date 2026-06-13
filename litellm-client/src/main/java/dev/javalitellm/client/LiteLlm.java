package dev.javalitellm.client;

import dev.javalitellm.callbacks.CallContext;
import dev.javalitellm.callbacks.LlmCallback;
import dev.javalitellm.core.ModelId;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.embedding.EmbeddingRequest;
import dev.javalitellm.core.embedding.EmbeddingResponse;
import dev.javalitellm.core.exception.LiteLlmException;
import dev.javalitellm.core.pricing.CostCalculator;
import dev.javalitellm.core.spi.LlmProvider;
import dev.javalitellm.core.spi.ProviderConfig;
import dev.javalitellm.core.spi.StreamHandler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * SDK entry point: routes a canonical request to the provider named in the model route string,
 * with retries on retryable failures.
 *
 * <pre>{@code
 * LiteLlm client = LiteLlm.builder()
 *         .apiKey("openai", System.getenv("OPENAI_API_KEY"))
 *         .build();
 * ChatResponse resp = client.chat(ChatRequest.builder()
 *         .model("openai/gpt-4o")
 *         .message(Message.user("Hello"))
 *         .build());
 * }</pre>
 */
public final class LiteLlm {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LiteLlm.class);

    private final ProviderRegistry registry;
    private final Map<String, ProviderConfig> configs;
    private final ProviderConfig defaultConfig;
    private final RetryPolicy retryPolicy;
    private final List<LlmCallback> callbacks;
    private final CostCalculator costCalculator = CostCalculator.bundled();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private LiteLlm(Builder builder) {
        this.registry = ProviderRegistry.discover();
        this.configs = Map.copyOf(builder.configs);
        this.defaultConfig = ProviderConfig.builder().timeout(builder.timeout).build();
        this.retryPolicy = builder.retryPolicy;
        this.callbacks = List.copyOf(builder.callbacks);
    }

    /** Hooks run on virtual threads; a failing hook is logged and never affects the request path. */
    private void fireCallbacks(java.util.function.Consumer<LlmCallback> invocation) {
        for (LlmCallback callback : callbacks) {
            executor.submit(() -> {
                try {
                    invocation.accept(callback);
                } catch (RuntimeException e) {
                    log.warn("llm callback {} threw", callback.getClass().getSimpleName(), e);
                }
            });
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public ChatResponse chat(ChatRequest request) {
        return chat(request, null);
    }

    /**
     * Like {@link #chat(ChatRequest)} but with an explicit per-call provider config, overriding the
     * builder-level configuration. Used by the router to apply per-deployment credentials.
     */
    public ChatResponse chat(ChatRequest request, ProviderConfig configOverride) {
        Route route = resolve(request.model(), configOverride);
        ChatRequest bare = request.toBuilder().model(route.id.model()).build();
        CallContext ctx = CallContext.create(route.id.provider(), request);
        fireCallbacks(cb -> cb.onRequest(ctx));
        try {
            ChatResponse response = withCost(route, withRetries(() -> route.provider.chat(bare, route.config)));
            Duration elapsed = Duration.between(ctx.startedAt(), java.time.Instant.now());
            fireCallbacks(cb -> cb.onSuccess(ctx, response, elapsed));
            return response;
        } catch (LiteLlmException e) {
            Duration elapsed = Duration.between(ctx.startedAt(), java.time.Instant.now());
            fireCallbacks(cb -> cb.onFailure(ctx, e, elapsed));
            throw e;
        }
    }

    public CompletableFuture<ChatResponse> chatAsync(ChatRequest request) {
        return CompletableFuture.supplyAsync(() -> chat(request), executor);
    }

    /** Streams via callback. Streaming calls are not retried once chunks may have been delivered. */
    public void chatStream(ChatRequest request, StreamHandler handler) {
        chatStream(request, null, handler);
    }

    /** Streaming variant of {@link #chat(ChatRequest, ProviderConfig)}. */
    public void chatStream(ChatRequest request, ProviderConfig configOverride, StreamHandler handler) {
        Route route = resolve(request.model(), configOverride);
        ChatRequest bare = request.toBuilder().model(route.id.model()).build();
        CallContext ctx = CallContext.create(route.id.provider(), request);
        fireCallbacks(cb -> cb.onRequest(ctx));
        ChunkAggregator aggregator = new ChunkAggregator();
        route.provider.chatStream(bare, route.config, new StreamHandler() {
            @Override
            public void onChunk(ChatChunk chunk) {
                aggregator.add(chunk);
                handler.onChunk(chunk);
            }

            @Override
            public void onComplete() {
                ChatResponse aggregated = withCost(route, aggregator.toResponse());
                Duration elapsed = Duration.between(ctx.startedAt(), java.time.Instant.now());
                fireCallbacks(cb -> cb.onStreamComplete(ctx, aggregated, elapsed));
                handler.onComplete();
            }

            @Override
            public void onError(LiteLlmException e) {
                Duration elapsed = Duration.between(ctx.startedAt(), java.time.Instant.now());
                fireCallbacks(cb -> cb.onFailure(ctx, e, elapsed));
                handler.onError(e);
            }
        });
    }

    /**
     * Streams as a lazy {@link Stream}; the provider call runs on a virtual thread. Failures surface
     * as {@link LiteLlmException} from the stream's terminal operation.
     */
    public Stream<ChatChunk> chatStream(ChatRequest request) {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(256);
        Object eos = new Object();
        executor.submit(() -> chatStream(request, new StreamHandler() {
            @Override
            public void onChunk(ChatChunk chunk) {
                putUninterruptibly(queue, chunk);
            }

            @Override
            public void onComplete() {
                putUninterruptibly(queue, eos);
            }

            @Override
            public void onError(LiteLlmException e) {
                putUninterruptibly(queue, e);
            }
        }));

        var iterator = new java.util.Iterator<ChatChunk>() {
            private Object next;

            @Override
            public boolean hasNext() {
                if (next == null) {
                    next = takeUninterruptibly(queue);
                }
                if (next instanceof LiteLlmException e) {
                    throw e;
                }
                return next != eos;
            }

            @Override
            public ChatChunk next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                ChatChunk chunk = (ChatChunk) next;
                next = null;
                return chunk;
            }
        };
        return StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(iterator, java.util.Spliterator.ORDERED), false);
    }

    public EmbeddingResponse embedding(EmbeddingRequest request) {
        return embedding(request, null);
    }

    /** Like {@link #embedding(EmbeddingRequest)} but with an explicit per-call provider config. */
    public EmbeddingResponse embedding(EmbeddingRequest request, ProviderConfig configOverride) {
        Route route = resolve(request.model(), configOverride);
        EmbeddingRequest bare =
                new EmbeddingRequest(route.id.model(), request.input(), request.dimensions(), request.extraParams());
        return withRetries(() -> route.provider.embedding(bare, route.config));
    }

    private record Route(ModelId id, LlmProvider provider, ProviderConfig config) {}

    private ChatResponse withCost(Route route, ChatResponse response) {
        if (response.costUsd() != null || response.usage() == null) {
            return response;
        }
        // Price lookup prefers the response-reported model (it carries the dated snapshot name),
        // falling back to the requested one.
        java.math.BigDecimal cost = costCalculator.cost(
                route.id.provider(), response.model() != null ? response.model() : route.id.model(), response.usage());
        if (cost == null) {
            cost = costCalculator.cost(route.id.provider(), route.id.model(), response.usage());
        }
        return cost == null
                ? response
                : new ChatResponse(
                        response.id(),
                        response.model(),
                        response.created(),
                        response.choices(),
                        response.usage(),
                        cost);
    }

    private Route resolve(String model) {
        return resolve(model, null);
    }

    private Route resolve(String model, ProviderConfig configOverride) {
        ModelId id = ModelId.parse(model);
        LlmProvider provider = registry.require(id.provider(), id.model());
        ProviderConfig config =
                configOverride != null ? configOverride : configs.getOrDefault(id.provider(), defaultConfig);
        return new Route(id, provider, config);
    }

    private <T> T withRetries(Supplier<T> call) {
        LiteLlmException last = null;
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            try {
                return call.get();
            } catch (LiteLlmException e) {
                last = e;
                if (!e.retryable() || attempt == retryPolicy.maxAttempts()) {
                    throw e;
                }
                sleep(retryPolicy.delayBeforeRetry(attempt));
            }
        }
        throw last;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void putUninterruptibly(BlockingQueue<Object> queue, Object item) {
        try {
            queue.put(item);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Object takeUninterruptibly(BlockingQueue<Object> queue) {
        try {
            return queue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LiteLlmException("interrupted while waiting for stream chunk", null, null, 0, false, e);
        }
    }

    public static final class Builder {
        private final Map<String, ProviderConfig> configs = new LinkedHashMap<>();
        private final Map<String, ProviderConfig.Builder> configBuilders = new LinkedHashMap<>();
        private final List<LlmCallback> callbacks = new ArrayList<>();
        private Duration timeout = ProviderConfig.DEFAULT_TIMEOUT;
        private RetryPolicy retryPolicy = RetryPolicy.exponential(3);

        public Builder callback(LlmCallback callback) {
            this.callbacks.add(callback);
            return this;
        }

        public Builder apiKey(String provider, String apiKey) {
            configBuilder(provider).apiKey(apiKey);
            return this;
        }

        public Builder apiBase(String provider, String apiBase) {
            configBuilder(provider).apiBase(apiBase);
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        private ProviderConfig.Builder configBuilder(String provider) {
            return configBuilders.computeIfAbsent(provider, k -> ProviderConfig.builder());
        }

        public LiteLlm build() {
            configBuilders.forEach((provider, cfg) ->
                    configs.put(provider, cfg.timeout(timeout).build()));
            return new LiteLlm(this);
        }
    }
}
