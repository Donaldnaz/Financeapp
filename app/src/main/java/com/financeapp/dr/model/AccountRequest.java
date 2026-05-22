package com.financeapp.dr.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountRequest(
        @NotBlank @Size(max = 120) String displayName,
        @NotBlank @Size(min = 3, max = 3) String currency
) {
}
