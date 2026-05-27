package com.financeapp.dr.assistant;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PendingActionStore {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final Map<String, PendingAction> actions = new ConcurrentHashMap<>();

    public PendingAction save(PendingAction action) {
        actions.put(action.id(), action);
        return action;
    }

    public Optional<PendingAction> getForUser(String id, String userId) {
        purgeExpired();
        PendingAction action = actions.get(id);
        if (action == null || !action.userId().equals(userId)) {
            return Optional.empty();
        }
        if (action.expiresAt().isBefore(Instant.now())) {
            actions.remove(id);
            return Optional.empty();
        }
        return Optional.of(action);
    }

    public void remove(String id) {
        actions.remove(id);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        actions.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    public static Instant defaultExpiry() {
        return Instant.now().plus(TTL);
    }
}
