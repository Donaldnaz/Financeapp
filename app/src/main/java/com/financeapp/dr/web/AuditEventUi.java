package com.financeapp.dr.web;

public final class AuditEventUi {

    private AuditEventUi() {
    }

    public static String badgeClass(String eventType) {
        if (eventType == null) {
            return "bg-bank-100 text-bank-800";
        }
        return switch (eventType) {
            case "SIGNUP" -> "bg-violet-100 text-violet-800";
            case "LOGIN_SUCCESS" -> "bg-emerald-100 text-emerald-800";
            case "LOGIN_FAILURE" -> "bg-rose-100 text-rose-800";
            case "TRANSFER_OUT", "TRANSFER_IN", "WITHDRAWAL", "WITHDRAWAL_PAYPAL" ->
                    "bg-amber-100 text-amber-800";
            case "DEPOSIT_CARD" -> "bg-emerald-100 text-emerald-800";
            case "ASSISTANT_QUERY" -> "bg-sky-100 text-sky-800";
            default -> "bg-bank-100 text-bank-800";
        };
    }

    public static String displayValue(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
