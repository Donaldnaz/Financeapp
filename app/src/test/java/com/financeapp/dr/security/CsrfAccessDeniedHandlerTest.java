package com.financeapp.dr.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfAccessDeniedHandlerTest {

    @Test
    void staleFormUrlUsesRefererForPaymentForms() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transfer");
        request.addHeader("Referer", "http://localhost:8080/withdraw");

        assertThat(CsrfAccessDeniedHandler.staleFormUrl(request)).isEqualTo("/withdraw?stale=1");
    }

    @Test
    void staleFormUrlFallsBackToRequestPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/deposit");

        assertThat(CsrfAccessDeniedHandler.staleFormUrl(request)).isEqualTo("/deposit?stale=1");
    }

    @Test
    void staleFormUrlDefaultsToDashboard() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");

        assertThat(CsrfAccessDeniedHandler.staleFormUrl(request)).isEqualTo("/dashboard?stale=1");
    }
}
