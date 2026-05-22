package com.financeapp.dr.model;

import java.time.Instant;

public record PaymentMethodResponse(
        String type,
        String brand,
        String maskedReference,
        String last4,
        Instant linkedAt
) {
}
