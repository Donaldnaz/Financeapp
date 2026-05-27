package com.financeapp.dr.assistant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AssistantRateLimiter {

    private static final int MAX_REQUESTS = 30;
    private static final long WINDOW_SECONDS = 60;

    private final Map<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    public void check(String userId) {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(WINDOW_SECONDS);
        Deque<Instant> window = buckets.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.removeFirst();
            }
            if (window.size() >= MAX_REQUESTS) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Assistant rate limit exceeded. Try again in a minute.");
            }
            window.addLast(now);
        }
    }
}
