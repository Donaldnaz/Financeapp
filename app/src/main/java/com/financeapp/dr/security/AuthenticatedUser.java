package com.financeapp.dr.security;

public record AuthenticatedUser(
        String userId,
        String username,
        String displayName,
        String accountId
) {
}
