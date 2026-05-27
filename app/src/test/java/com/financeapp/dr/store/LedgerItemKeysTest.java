package com.financeapp.dr.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerItemKeysTest {

    @Test
    void parsesAccountIdFromPk() {
        assertThat(LedgerItemKeys.parseAccountId("ACCOUNT#01JABCDEF")).isEqualTo("01JABCDEF");
    }

    @Test
    void parsesTransactionIdFromSk() {
        assertThat(LedgerItemKeys.parseTransactionId("TXN#01JABCDEF")).isEqualTo("01JABCDEF");
    }

    @Test
    void parsesEventIdFromSk() {
        assertThat(LedgerItemKeys.parseEventId("EVENT#01JABCDEF")).isEqualTo("01JABCDEF");
    }

    @Test
    void parsesUsernameFromPk() {
        assertThat(LedgerItemKeys.parseUsername("USER#alice")).isEqualTo("alice");
    }

    @Test
    void extractsLast4FromMaskedReference() {
        assertThat(LedgerItemKeys.extractLast4FromMaskedReference("**** **** **** 1234")).isEqualTo("1234");
        assertThat(LedgerItemKeys.extractLast4FromMaskedReference("not-a-card")).isNull();
    }

    @Test
    void resolveAuditUserIdFallsBackToUnknown() {
        assertThat(LedgerItemKeys.resolveAuditUserId(null)).isEqualTo(LedgerItemKeys.AUDIT_UNKNOWN_USER);
        assertThat(LedgerItemKeys.resolveAuditUserId("  ")).isEqualTo(LedgerItemKeys.AUDIT_UNKNOWN_USER);
        assertThat(LedgerItemKeys.resolveAuditUserId("user-1")).isEqualTo("user-1");
    }

    @Test
    void rejectsInvalidKeys() {
        assertThatThrownBy(() -> LedgerItemKeys.parseAccountId("USER#alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
