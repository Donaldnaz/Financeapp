package com.financeapp.dr.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TransferRequest(
        @NotBlank
        @Size(min = 3, max = 30)
        String toUsername,

        @NotNull
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        BigDecimal amount,

        @Size(max = 256)
        String memo
) {
    public String memoOrDefault() {
        return memo == null || memo.isBlank() ? "Transfer" : memo;
    }
}
