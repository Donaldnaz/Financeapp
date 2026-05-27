package com.financeapp.dr.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceReturnUrlTest {

    @Test
    void safeReturnUrlRejectsErrorPath() {
        assertThat(AuthService.safeReturnUrl("/error")).isEqualTo("/dashboard");
        assertThat(AuthService.safeReturnUrl("/error?returnUrl=/error")).isEqualTo("/dashboard");
        assertThat(AuthService.safeReturnUrl("/error/nested")).isEqualTo("/dashboard");
    }

    @Test
    void safeReturnUrlKeepsNormalPaths() {
        assertThat(AuthService.safeReturnUrl("/transfer")).isEqualTo("/transfer");
        assertThat(AuthService.safeReturnUrl("/dashboard")).isEqualTo("/dashboard");
    }

    @Test
    void safeReturnUrlDefaultsWhenBlankOrExternal() {
        assertThat(AuthService.safeReturnUrl(null)).isEqualTo("/dashboard");
        assertThat(AuthService.safeReturnUrl("//evil.example")).isEqualTo("/dashboard");
    }
}
