package com.financeapp.dr.web;

import com.financeapp.dr.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class AuthenticatedUserAdvice {

    @ModelAttribute("user")
    public AuthenticatedUser user(@AuthenticationPrincipal AuthenticatedUser user) {
        return user;
    }
}
