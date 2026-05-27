package com.financeapp.dr.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UsernameInputTest {

    @Test
    void normalizeRecipientStripsAtPrefixAndWhitespace() {
        assertThat(UsernameInput.normalizeRecipient("  @Bob  ")).isEqualTo("bob");
    }

    @Test
    void normalizeRecipientHandlesNull() {
        assertThat(UsernameInput.normalizeRecipient(null)).isEmpty();
    }
}
