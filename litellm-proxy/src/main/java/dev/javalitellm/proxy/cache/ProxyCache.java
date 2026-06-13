package dev.javalitellm.proxy.cache;

import dev.javalitellm.cache.LlmCache;
import dev.javalitellm.core.chat.ChatResponse;
import java.util.Optional;

/** Cache facade for the gateway; a null delegate means caching is disabled in config. */
public final class ProxyCache {

    private final LlmCache delegate;

    public ProxyCache(LlmCache delegate) {
        this.delegate = delegate;
    }

    public boolean enabled() {
        return delegate != null;
    }

    public Optional<ChatResponse> get(String key) {
        return delegate == null ? Optional.empty() : delegate.get(key);
    }

    public void put(String key, ChatResponse response) {
        if (delegate != null) {
            delegate.put(key, response);
        }
    }
}
