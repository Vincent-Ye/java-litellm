package dev.javalitellm.cache;

import dev.javalitellm.core.chat.ChatResponse;
import java.util.Optional;

/**
 * Exact-match response cache. Keys come from {@link ChatCacheKey#of}, which hashes the canonical
 * request. Implementations must be thread-safe.
 */
public interface LlmCache {

    Optional<ChatResponse> get(String key);

    void put(String key, ChatResponse response);
}
