package com.financeapp.dr.security;

import com.financeapp.dr.service.AuthService;
import com.financeapp.dr.security.ReturnUrlSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class RedirectToLoginEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (Boolean.TRUE.equals(request.getAttribute(AuthService.JWT_EXPIRED_ATTRIBUTE))) {
            response.sendRedirect("/login?expired=1");
            return;
        }
        String path = request.getRequestURI();
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            path = path + "?" + query;
        }
        String formReturn = ReturnUrlSupport.loginFormReturnUrl(path);
        if (formReturn == null) {
            response.sendRedirect("/login");
            return;
        }
        String returnUrl = UriUtils.encodeQueryParam(formReturn, StandardCharsets.UTF_8);
        response.sendRedirect("/login?returnUrl=" + returnUrl);
    }
}
