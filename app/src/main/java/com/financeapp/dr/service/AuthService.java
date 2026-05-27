package com.financeapp.dr.service;

import com.financeapp.dr.config.AppProperties;
import com.financeapp.dr.model.UserResponse;
import com.financeapp.dr.model.UserWithPasswordHash;
import com.financeapp.dr.security.AuthenticatedUser;
import com.financeapp.dr.security.JwtAuthFilter;
import com.financeapp.dr.security.JwtService;
import com.financeapp.dr.security.ReturnUrlSupport;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AuthService {

    public static final String JWT_EXPIRED_ATTRIBUTE = "JWT_EXPIRED";
    private static final long LOGIN_FAILURE_DELAY_MS = 250L;

    private final LedgerService ledger;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties properties;

    public AuthService(LedgerService ledger,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AppProperties properties) {
        this.ledger = ledger;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    public Optional<UserResponse> authenticate(String username, String password, String ip, String userAgent) {
        Optional<UserWithPasswordHash> userOpt = ledger.findUserByUsername(username);
        boolean ok = userOpt.isPresent() && passwordEncoder.matches(password, userOpt.get().passwordHash());
        if (!ok) {
            sleep(LOGIN_FAILURE_DELAY_MS + ThreadLocalRandom.current().nextLong(0, 75));
            ledger.recordLoginFailure(username, ip, userAgent,
                    userOpt.isPresent() ? "bad_password" : "unknown_user");
            return Optional.empty();
        }
        return Optional.of(userOpt.get().user());
    }

    public AuthSession issueSession(UserResponse user, HttpServletResponse response) {
        String token = jwtService.issue(toPrincipal(user));
        response.addCookie(buildAuthCookie(token, (int) jwtService.validity().getSeconds()));
        return new AuthSession(token, user);
    }

    public void recordLoginSuccess(UserResponse user, String ip, String userAgent) {
        ledger.recordLoginSuccess(user.userId(), user.username(), ip, userAgent);
    }

    public void clearSession(HttpServletResponse response) {
        response.addCookie(clearAuthCookie());
    }

    public AuthenticatedUser toPrincipal(UserResponse user) {
        return new AuthenticatedUser(
                user.userId(), user.username(), user.displayName(), user.defaultAccountId());
    }

    public static String safeReturnUrl(String returnUrl) {
        return ReturnUrlSupport.safeReturnUrl(returnUrl);
    }

    private Cookie buildAuthCookie(String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(JwtAuthFilter.COOKIE_NAME, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(properties.cookie().secure());
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSeconds);
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }

    private Cookie clearAuthCookie() {
        Cookie cookie = new Cookie(JwtAuthFilter.COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(properties.cookie().secure());
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public record AuthSession(String token, UserResponse user) {
    }
}
