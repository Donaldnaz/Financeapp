package com.financeapp.dr.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

import java.io.IOException;
import java.net.URI;

/**
 * Sends users back to the payment form with a refresh hint instead of the confusing
 * {@code /login?returnUrl=/error} loop for still-authenticated sessions.
 */
public class CsrfAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        if (accessDeniedException instanceof InvalidCsrfTokenException
                || accessDeniedException instanceof MissingCsrfTokenException) {
            response.sendRedirect(staleFormUrl(request));
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }

    static String staleFormUrl(HttpServletRequest request) {
        String refererPath = refererPath(request);
        if (isStaleTarget(refererPath)) {
            return refererPath + "?stale=1";
        }
        String requestPath = request.getRequestURI();
        if (isStaleTarget(requestPath)) {
            return requestPath + "?stale=1";
        }
        return "/dashboard?stale=1";
    }

    private static boolean isStaleTarget(String path) {
        return "/transfer".equals(path)
                || "/deposit".equals(path)
                || "/withdraw".equals(path)
                || "/login".equals(path)
                || "/signup".equals(path);
    }

    private static String refererPath(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            return URI.create(referer).getPath();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
