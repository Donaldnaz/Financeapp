package com.financeapp.dr.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final Duration validity;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.validityMinutes:60}") long validityMinutes) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes (256 bits).");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.validity = Duration.ofMinutes(validityMinutes);
    }

    public String issue(AuthenticatedUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.userId())
                .claims(Map.of(
                        "username", user.username(),
                        "displayName", user.displayName(),
                        "accountId", user.accountId()
                ))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(validity)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public AuthenticatedUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new AuthenticatedUser(
                claims.getSubject(),
                claims.get("username", String.class),
                claims.get("displayName", String.class),
                claims.get("accountId", String.class)
        );
    }

    public Duration validity() {
        return validity;
    }
}
