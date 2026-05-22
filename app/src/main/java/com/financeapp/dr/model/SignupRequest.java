package com.financeapp.dr.model;

import com.financeapp.dr.security.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank
        @Size(min = 3, max = 30)
        @Pattern(regexp = "^[a-z0-9._-]+$",
                 message = "Username may contain only lowercase letters, digits, dots, underscores, and dashes.")
        String username,

        @NotBlank
        @Size(min = 1, max = 80)
        String displayName,

        @StrongPassword
        String password,

        @NotBlank
        String confirmPassword
) {
    public boolean passwordsMatch() {
        return password != null && password.equals(confirmPassword);
    }
}
