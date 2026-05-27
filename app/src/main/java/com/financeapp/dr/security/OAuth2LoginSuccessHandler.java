package com.financeapp.dr.security;

import com.financeapp.dr.model.UserResponse;
import com.financeapp.dr.service.AuthService;
import com.financeapp.dr.service.LedgerService;
import com.financeapp.dr.web.ClientInfo;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final LedgerService ledger;
    private final AuthService authService;

    public OAuth2LoginSuccessHandler(LedgerService ledger, AuthService authService) {
        this.ledger = ledger;
        this.authService = authService;
        setDefaultTargetUrl("/dashboard");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        OAuth2AuthenticationToken oauth = (OAuth2AuthenticationToken) authentication;
        OAuth2User oauthUser = oauth.getPrincipal();
        String sub = oauthUser.getAttribute("sub");
        if (sub == null || sub.isBlank()) {
            getRedirectStrategy().sendRedirect(request, response, "/login?error=1");
            return;
        }

        String username = "google_" + sub;
        String displayName = oauthUser.getAttribute("name");
        if (displayName == null || displayName.isBlank()) {
            displayName = oauthUser.getAttribute("email");
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = username;
        }

        String ip = ClientInfo.ip(request);
        String ua = ClientInfo.userAgent(request);
        boolean created = ledger.findUserByUsername(username).isEmpty();
        UserResponse user = ledger.signupOAuth(username, displayName, ip, ua);

        authService.issueSession(user, response);
        authService.recordLoginSuccess(user, ip, ua);

        String target = created ? "/dashboard?welcome=1" : "/dashboard";
        getRedirectStrategy().sendRedirect(request, response, target);
    }
}
