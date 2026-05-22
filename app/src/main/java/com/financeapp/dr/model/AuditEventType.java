package com.financeapp.dr.model;

public final class AuditEventType {
    public static final String SIGNUP = "SIGNUP";
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String DEPOSIT = "DEPOSIT";
    public static final String DEPOSIT_CARD = "DEPOSIT_CARD";
    public static final String WITHDRAWAL = "WITHDRAWAL";
    public static final String WITHDRAWAL_PAYPAL = "WITHDRAWAL_PAYPAL";
    public static final String TRANSFER_OUT = "TRANSFER_OUT";
    public static final String TRANSFER_IN = "TRANSFER_IN";

    private AuditEventType() {
    }
}
