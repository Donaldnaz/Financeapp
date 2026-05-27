package com.financeapp.dr.model;

public record AuthResponse(
        String token,
        String userId,
        String username,
        String displayName,
        String accountId
) {
    public static AuthResponse from(String token, UserResponse user) {
        return new AuthResponse(
                token,
                user.userId(),
                user.username(),
                user.displayName(),
                user.defaultAccountId());
    }
}
