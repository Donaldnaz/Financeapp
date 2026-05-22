package com.financeapp.dr.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record WithdrawRequest(
        @NotNull
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        BigDecimal amount,

        @Size(max = 256)
        String description,

        @Email(message = "PayPal email must be valid")
        @Size(max = 254)
        String paypalEmail
) {
    public String descriptionOrDefault() {
        return description == null || description.isBlank() ? "Withdrawal to PayPal" : description;
    }
}
