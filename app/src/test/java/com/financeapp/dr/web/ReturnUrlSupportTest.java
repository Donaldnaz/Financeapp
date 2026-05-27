package com.financeapp.dr.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnUrlSupportTest {

    @Test
    void safeReturnUrlRejectsErrorPaths() {
        assertThat(ReturnUrlSupport.safeReturnUrl("/error")).isEqualTo("/dashboard");
        assertThat(ReturnUrlSupport.safeReturnUrl("/error?returnUrl=/error")).isEqualTo("/dashboard");
        assertThat(ReturnUrlSupport.safeReturnUrl("/error/nested")).isEqualTo("/dashboard");
    }

    @Test
    void loginFormReturnUrlOmitsBlockedPaths() {
        assertThat(ReturnUrlSupport.loginFormReturnUrl("/error")).isNull();
        assertThat(ReturnUrlSupport.loginFormReturnUrl("/dashboard")).isNull();
        assertThat(ReturnUrlSupport.loginFormReturnUrl("/login")).isNull();
    }

    @Test
    void loginFormReturnUrlKeepsDeepLinks() {
        assertThat(ReturnUrlSupport.loginFormReturnUrl("/transfer")).isEqualTo("/transfer");
        assertThat(ReturnUrlSupport.loginFormReturnUrl("/audit?cursor=abc")).isEqualTo("/audit?cursor=abc");
    }

    @Test
    void shouldResetLoginUrlForPoisonedReturnUrls() {
        assertThat(ReturnUrlSupport.shouldResetLoginUrl("/error?returnUrl=/error")).isTrue();
        assertThat(ReturnUrlSupport.shouldResetLoginUrl("/transfer")).isFalse();
    }
}
