package com.financeapp.dr.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class StrongPasswordValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "S3cure-Banking!Pass",
            "Strong#Pass2026!Bank",
            "MyV@ult-1ofGold!2026"
    })
    void acceptsStrongPasswords(String pw) {
        assertThat(StrongPasswordValidator.firstFailure(pw)).isNull();
    }

    @ParameterizedTest
    @CsvSource(value = {
            "'short1!', at least 12",
            "'alllower12345!', uppercase",
            "'ALLUPPER12345!', lowercase",
            "'NoDigits!Symbols', digit",
            "'NoSymbols1234567', symbol",
            "'Password123!', too common",
            "'', required"
    })
    void rejectsWeakPasswords(String pw, String expectedSubstring) {
        String reason = StrongPasswordValidator.firstFailure(pw);
        assertThat(reason).isNotNull();
        assertThat(reason.toLowerCase()).contains(expectedSubstring.toLowerCase());
    }

    @org.junit.jupiter.api.Test
    void nullIsRejected() {
        assertThat(StrongPasswordValidator.firstFailure(null)).isNotNull();
    }
}
