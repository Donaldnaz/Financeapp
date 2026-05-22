package com.financeapp.dr.model;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        String transactionId,
        String accountId,
        String type,
        BigDecimal amount,
        BigDecimal signedAmount,
        BigDecimal balanceAfter,
        String currency,
        String description,
        String status,
        String region,
        Instant createdAt,
        String createdByUserId,
        String createdByUsername,
        String counterpartyAccountId,
        String counterpartyUsername,
        String paymentMethod,
        String paymentReference
) {
    public boolean isCredit() {
        return signedAmount != null && signedAmount.signum() > 0;
    }
}
