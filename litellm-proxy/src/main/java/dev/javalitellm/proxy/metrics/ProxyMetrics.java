package dev.javalitellm.proxy.metrics;

import dev.javalitellm.core.chat.ChatResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Gateway metrics, tagged by model group; scrape via {@code /actuator/prometheus}. */
@Component
public class ProxyMetrics {

    private final MeterRegistry registry;

    public ProxyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRequest(String modelGroup, String outcome, Duration elapsed) {
        Timer.builder("litellm.request.duration")
                .tag("model_group", modelGroup)
                .tag("outcome", outcome)
                .register(registry)
                .record(elapsed);
    }

    public void recordUsage(String modelGroup, ChatResponse response) {
        if (response.usage() != null) {
            counter("litellm.tokens", modelGroup, "type", "prompt")
                    .increment(response.usage().promptTokens());
            counter("litellm.tokens", modelGroup, "type", "completion")
                    .increment(response.usage().completionTokens());
        }
        if (response.costUsd() != null) {
            counter("litellm.spend.usd", modelGroup, "type", "total")
                    .increment(response.costUsd().doubleValue());
        }
    }

    public void recordCache(String modelGroup, boolean hit) {
        counter("litellm.cache.requests", modelGroup, "result", hit ? "hit" : "miss")
                .increment();
    }

    public void recordRateLimited(String modelGroup, String limitType) {
        counter("litellm.rate.limited", modelGroup, "limit", limitType).increment();
    }

    private Counter counter(String name, String modelGroup, String extraTag, String extraValue) {
        return Counter.builder(name)
                .tag("model_group", modelGroup)
                .tag(extraTag, extraValue)
                .register(registry);
    }
}
