package com.financeapp.dr.web;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientInfo {

    private ClientInfo() {
    }

    public static String ip(HttpServletRequest req) {
        if (req == null) return null;
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr();
    }

    public static String userAgent(HttpServletRequest req) {
        return req == null ? null : req.getHeader("User-Agent");
    }
}
