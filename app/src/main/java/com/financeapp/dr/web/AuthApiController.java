package com.financeapp.dr.web;

import com.financeapp.dr.model.AuthResponse;
import com.financeapp.dr.model.LoginRequest;
import com.financeapp.dr.model.UserResponse;
import com.financeapp.dr.security.AuthenticatedUser;
import com.financeapp.dr.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthApiController {

    private final AuthService authService;

    public AuthApiController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        String ip = ClientInfo.ip(httpRequest);
        String ua = ClientInfo.userAgent(httpRequest);
        Optional<UserResponse> userOpt = authService.authenticate(
                request.username(), request.password(), ip, ua);
        if (userOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        UserResponse user = userOpt.get();
        AuthService.AuthSession session = authService.issueSession(user, httpResponse);
        authService.recordLoginSuccess(user, ip, ua);
        return ResponseEntity.ok(AuthResponse.from(session.token(), user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        authService.clearSession(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return ResponseEntity.ok(Map.of(
                "userId", user.userId(),
                "username", user.username(),
                "displayName", user.displayName(),
                "accountId", user.accountId()
        ));
    }
}
