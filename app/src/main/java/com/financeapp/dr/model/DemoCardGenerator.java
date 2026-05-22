package com.financeapp.dr.model;

import java.time.Instant;

public final class DemoCardGenerator {

    private DemoCardGenerator() {
    }

    public static PaymentMethodResponse create(String username) {
        int hash = Math.abs(username.toLowerCase().hashCode()) % 9000 + 1000;
        String last4 = String.format("%04d", hash);
        String masked = "**** **** **** " + last4;
        return new PaymentMethodResponse(
                PaymentMethodType.DEMO_CARD,
                "VISA",
                masked,
                last4,
                Instant.now());
    }
}
