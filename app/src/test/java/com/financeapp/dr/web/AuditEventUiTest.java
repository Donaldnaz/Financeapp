package com.financeapp.dr.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventUiTest {

    @Test
    void badgeClassMapsKnownEventTypes() {
        assertThat(AuditEventUi.badgeClass("SIGNUP")).contains("violet");
        assertThat(AuditEventUi.badgeClass("LOGIN_SUCCESS")).contains("emerald");
        assertThat(AuditEventUi.badgeClass("TRANSFER_OUT")).contains("amber");
        assertThat(AuditEventUi.badgeClass("DEPOSIT_CARD")).contains("emerald");
    }

    @Test
    void displayValueUsesDashForBlankValues() {
        assertThat(AuditEventUi.displayValue(null)).isEqualTo("—");
        assertThat(AuditEventUi.displayValue("  ")).isEqualTo("—");
        assertThat(AuditEventUi.displayValue("127.0.0.1")).isEqualTo("127.0.0.1");
    }
}
