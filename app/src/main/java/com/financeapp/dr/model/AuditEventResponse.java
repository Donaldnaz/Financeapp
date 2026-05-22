package com.financeapp.dr.model;

import java.time.Instant;

public record AuditEventResponse(
        String eventId,
        String eventType,
        String region,
        String ip,
        String userAgent,
        String details,
        Instant createdAt
) {
}
