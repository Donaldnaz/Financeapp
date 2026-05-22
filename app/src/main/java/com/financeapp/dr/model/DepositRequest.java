package com.financeapp.dr.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record DepositRequest(
        @NotNull
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        BigDecimal amount,

        @Size(max = 256)
        String description,

        @Size(max = 32)
        String paymentMethod
) {
    public String descriptionOrDefault() {
        return description == null || description.isBlank() ? "Deposit" : description;
    }

    public String paymentMethodOrDefault() {
        return paymentMethod == null || paymentMethod.isBlank()
                ? PaymentMethodType.DEMO_CARD
                : paymentMethod;
    }
}
