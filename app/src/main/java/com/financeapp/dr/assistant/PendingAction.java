package com.financeapp.dr.assistant;

import java.math.BigDecimal;
import java.time.Instant;

public record PendingAction(
        String id,
        String userId,
        String type,
        String summary,
        Instant expiresAt,
        String toUsername,
        BigDecimal amount,
        String memo,
        String paypalEmail
) {
}
