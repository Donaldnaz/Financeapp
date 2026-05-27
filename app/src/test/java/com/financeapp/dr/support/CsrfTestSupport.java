package com.financeapp.dr.support;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

public final class CsrfTestSupport {

    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_PARAM = "_csrf";

    private CsrfTestSupport() {
    }

    public static CsrfTokens fetch(MockMvc mockMvc, String url, Cookie... cookies) throws Exception {
        var request = get(url);
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie != null) {
                    request = request.cookie(cookie);
                }
            }
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        Cookie csrfCookie = result.getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrfCookie).as("CSRF cookie from %s", url).isNotNull();
        assertThat(csrfCookie.getValue()).isNotBlank();
        return new CsrfTokens(csrfCookie.getValue(), csrfCookie);
    }

    public record CsrfTokens(String token, Cookie cookie) {
    }
}
