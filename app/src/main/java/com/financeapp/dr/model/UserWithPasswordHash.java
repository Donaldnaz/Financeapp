package com.financeapp.dr.model;

public record UserWithPasswordHash(
        UserResponse user,
        String passwordHash
) {
}
