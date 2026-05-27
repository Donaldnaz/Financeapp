package com.financeapp.dr.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class AuthUiAdvice {

    @Value("${GOOGLE_CLIENT_ID:}")
    private String googleClientId;

    @ModelAttribute("googleLoginEnabled")
    public boolean googleLoginEnabled() {
        return googleClientId != null && !googleClientId.isBlank();
    }
}
