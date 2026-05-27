package com.financeapp.dr.web;

import java.util.Locale;

public final class UsernameInput {

    private UsernameInput() {
    }

    public static String normalizeRecipient(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
