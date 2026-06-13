package dev.javalitellm.cache;

import static org.assertj.core.api.Assertions.assertThat;

import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Message;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatCacheKeyTest {

    private static ChatRequest.Builder base() {
        return ChatRequest.builder().model("gpt-4o").message(Message.user("hi"));
    }

    @Test
    void identicalRequestsShareAKey() {
        assertThat(ChatCacheKey.of(base().temperature(0.2).build()))
                .isEqualTo(ChatCacheKey.of(base().temperature(0.2).build()));
    }

    @Test
    void semanticDifferencesChangeTheKey() {
        String baseKey = ChatCacheKey.of(base().build());
        assertThat(ChatCacheKey.of(base().temperature(0.9).build())).isNotEqualTo(baseKey);
        assertThat(ChatCacheKey.of(ChatRequest.builder()
                        .model("gpt-4o")
                        .message(Message.user("other"))
                        .build()))
                .isNotEqualTo(baseKey);
        assertThat(ChatCacheKey.of(ChatRequest.builder()
                        .model("claude")
                        .message(Message.user("hi"))
                        .build()))
                .isNotEqualTo(baseKey);
    }

    @Test
    void extraParamOrderDoesNotMatter() {
        String a = ChatCacheKey.of(base().extraParam("b", 2).extraParam("a", 1).build());
        String b = ChatCacheKey.of(base().extraParam("a", 1).extraParam("b", 2).build());
        assertThat(a).isEqualTo(b);
    }

    @Test
    void caffeineCacheStoresAndExpires() throws InterruptedException {
        CaffeineLlmCache cache = new CaffeineLlmCache(Duration.ofMillis(80), 100);
        ChatResponse response = new ChatResponse("id", "gpt-4o", 0, List.of(), null, null);

        cache.put("k", response);
        assertThat(cache.get("k")).contains(response);
        Thread.sleep(120);
        assertThat(cache.get("k")).isEmpty();
    }
}
