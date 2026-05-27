package com.financeapp.dr.assistant.model;

import java.time.Instant;

public record PendingActionResponse(String id, String summary, Instant expiresAt) {
}
