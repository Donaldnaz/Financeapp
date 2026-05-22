package com.financeapp.dr.model;

public record TransferResult(
        TransactionResponse senderTransaction,
        TransactionResponse receiverTransaction
) {
}
