package com.financeapp.dr.store;

/**
 * Shared pk/sk conventions for the single-table ledger layout.
 */
public final class LedgerItemKeys {

    public static final String SK_METADATA = "METADATA";
    public static final String SK_CARD_DEMO = "CARD#DEMO";
    public static final String SK_PAYPAL_DEMO = "PAYPAL#DEMO";

    public static final String PREFIX_ACCOUNT = "ACCOUNT#";
    public static final String PREFIX_TXN = "TXN#";
    public static final String PREFIX_EVENT = "EVENT#";
    public static final String PREFIX_USER = "USER#";
    public static final String PREFIX_IDEMPOTENCY = "IDEMPOTENCY#";
    public static final String PREFIX_AUDIT_USER = "AUDIT#USER#";
    public static final String PREFIX_PAYMENT_USER = "PAYMENT#USER#";

    public static final String DEFAULT_CURRENCY = "USD";
    public static final String DEFAULT_STATUS = "POSTED";
    public static final String DEMO_CARD_BRAND = "VISA";
    public static final String AUDIT_UNKNOWN_USER = "UNKNOWN";

    private LedgerItemKeys() {
    }

    public static String accountPk(String accountId) {
        return PREFIX_ACCOUNT + accountId;
    }

    public static String parseAccountId(String pk) {
        requirePrefix(pk, PREFIX_ACCOUNT, "account");
        return pk.substring(PREFIX_ACCOUNT.length());
    }

    public static String txnSk(String transactionId) {
        return PREFIX_TXN + transactionId;
    }

    public static String parseTransactionId(String sk) {
        requirePrefix(sk, PREFIX_TXN, "transaction");
        return sk.substring(PREFIX_TXN.length());
    }

    public static String transactionGsiPk(String transactionId) {
        return PREFIX_TXN + transactionId;
    }

    public static String parseEventId(String sk) {
        requirePrefix(sk, PREFIX_EVENT, "audit event");
        return sk.substring(PREFIX_EVENT.length());
    }

    public static String idempotencyPk(String requestId) {
        return PREFIX_IDEMPOTENCY + requestId;
    }

    public static String userPk(String username) {
        return PREFIX_USER + username;
    }

    public static String parseUsername(String pk) {
        requirePrefix(pk, PREFIX_USER, "user");
        return pk.substring(PREFIX_USER.length());
    }

    public static String paymentUserPk(String userId) {
        return PREFIX_PAYMENT_USER + userId;
    }

    public static String auditUserPk(String userId) {
        return PREFIX_AUDIT_USER + userId;
    }

    public static String resolveAuditUserId(String userId) {
        if (userId != null && !userId.isBlank()) {
            return userId;
        }
        return AUDIT_UNKNOWN_USER;
    }

    public static String extractLast4FromMaskedReference(String maskedReference) {
        if (maskedReference == null || maskedReference.isBlank()) {
            return null;
        }
        String trimmed = maskedReference.trim();
        if (trimmed.length() < 4) {
            return null;
        }
        String suffix = trimmed.substring(trimmed.length() - 4);
        if (suffix.chars().allMatch(Character::isDigit)) {
            return suffix;
        }
        return null;
    }

    private static void requirePrefix(String value, String prefix, String label) {
        if (value == null || !value.startsWith(prefix)) {
            throw new IllegalArgumentException("Invalid " + label + " key: " + value);
        }
    }
}
