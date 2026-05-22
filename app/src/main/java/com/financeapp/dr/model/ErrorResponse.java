package com.financeapp.dr.model;

import java.time.Instant;

public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String requestId
) {
}
