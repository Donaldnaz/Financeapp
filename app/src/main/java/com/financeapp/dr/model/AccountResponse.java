package com.financeapp.dr.model;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
        String accountId,
        String displayName,
        String currency,
        BigDecimal balance,
        Instant createdAt
) {
    public String maskedAccountNumber() {
        if (accountId == null || accountId.length() < 8) {
            return "****";
        }
        return "****" + accountId.substring(accountId.length() - 8);
    }
}
