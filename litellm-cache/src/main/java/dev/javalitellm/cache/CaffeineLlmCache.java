package dev.javalitellm.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.javalitellm.core.chat.ChatResponse;
import java.time.Duration;
import java.util.Optional;

/** In-process cache; the Redis tier for multi-replica deployments arrives in a later milestone. */
public final class CaffeineLlmCache implements LlmCache {

    private final Cache<String, ChatResponse> cache;

    public CaffeineLlmCache(Duration ttl, long maxEntries) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxEntries)
                .build();
    }

    @Override
    public Optional<ChatResponse> get(String key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    @Override
    public void put(String key, ChatResponse response) {
        cache.put(key, response);
    }
}
