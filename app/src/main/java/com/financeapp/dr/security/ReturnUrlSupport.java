package com.financeapp.dr.security;

/**
 * Validates browser return URLs to prevent open redirects and the Spring Security
 * {@code /error} redirect loop that surfaces as status {@code 999} / {@code None}.
 */
public final class ReturnUrlSupport {

    private static final String DEFAULT = "/dashboard";

    private ReturnUrlSupport() {
    }

    public static String safeReturnUrl(String returnUrl) {
        String sanitized = sanitize(returnUrl);
        return sanitized != null ? sanitized : DEFAULT;
    }

    /**
     * Return URL safe to echo into the login form, or {@code null} to omit the hidden field.
     */
    public static String loginFormReturnUrl(String returnUrl) {
        String sanitized = sanitize(returnUrl);
        if (sanitized == null || DEFAULT.equals(sanitized)) {
            return null;
        }
        return sanitized;
    }

    public static boolean isErrorPath(String returnUrl) {
        return "/error".equals(pathOnly(returnUrl)) || pathOnly(returnUrl).startsWith("/error/");
    }

    public static boolean shouldResetLoginUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return false;
        }
        return isErrorPath(returnUrl) || returnUrl.length() > 512;
    }

    private static String sanitize(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return null;
        }
        String trimmed = returnUrl.trim();
        if (!trimmed.startsWith("/") || trimmed.startsWith("//")) {
            return null;
        }
        if (isErrorPath(trimmed)) {
            return null;
        }
        String path = pathOnly(trimmed);
        if ("/login".equals(path) || "/logout".equals(path)) {
            return null;
        }
        return trimmed;
    }

    private static String pathOnly(String returnUrl) {
        int queryIdx = returnUrl.indexOf('?');
        return queryIdx >= 0 ? returnUrl.substring(0, queryIdx) : returnUrl;
    }
}
