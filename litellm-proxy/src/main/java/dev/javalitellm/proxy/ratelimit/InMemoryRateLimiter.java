package dev.javalitellm.proxy.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/** One-minute sliding windows per scope. */
public final class InMemoryRateLimiter implements RateLimiter {

    private static final long WINDOW_MILLIS = 60_000;

    private record Event(long atMillis, int tokens) {}

    private final Map<String, ConcurrentLinkedDeque<Event>> requests = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedDeque<Event>> tokens = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquireRequest(String scope, Integer rpmLimit) {
        if (rpmLimit == null) {
            return true;
        }
        ConcurrentLinkedDeque<Event> window = requests.computeIfAbsent(scope, k -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();
        prune(window, now);
        // benign race: a burst may briefly overshoot by the number of racing threads
        if (window.size() >= rpmLimit) {
            return false;
        }
        window.addLast(new Event(now, 0));
        return true;
    }

    @Override
    public boolean withinTokenLimit(String scope, Integer tpmLimit) {
        if (tpmLimit == null) {
            return true;
        }
        ConcurrentLinkedDeque<Event> window = tokens.computeIfAbsent(scope, k -> new ConcurrentLinkedDeque<>());
        prune(window, System.currentTimeMillis());
        return window.stream().mapToInt(Event::tokens).sum() < tpmLimit;
    }

    @Override
    public void recordTokens(String scope, int tokenCount) {
        ConcurrentLinkedDeque<Event> window = tokens.computeIfAbsent(scope, k -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();
        window.addLast(new Event(now, tokenCount));
        prune(window, now);
    }

    private static void prune(ConcurrentLinkedDeque<Event> window, long now) {
        Event head;
        while ((head = window.peekFirst()) != null && now - head.atMillis() > WINDOW_MILLIS) {
            window.pollFirst();
        }
    }
}
