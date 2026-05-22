package com.financeapp.dr.model;

import java.time.Instant;

public record UserResponse(
        String userId,
        String username,
        String displayName,
        String defaultAccountId,
        Instant createdAt
) {
}
