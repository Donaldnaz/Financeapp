package com.financeapp.dr.security;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

@Component
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;
    public static final String SYMBOL_REGEX = "[!\"#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~]";

    private static final Logger log = LoggerFactory.getLogger(StrongPasswordValidator.class);

    private static final Set<String> BLOCKLIST = loadBlocklist();

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        String reason = firstFailure(value);
        if (reason == null) {
            return true;
        }
        ctx.disableDefaultConstraintViolation();
        ctx.buildConstraintViolationWithTemplate(reason).addConstraintViolation();
        return false;
    }

    public static String firstFailure(String value) {
        if (value == null || value.isBlank()) {
            return "Password is required.";
        }
        if (value.length() < MIN_LENGTH) {
            return "Password must be at least " + MIN_LENGTH + " characters.";
        }
        if (value.length() > MAX_LENGTH) {
            return "Password must be at most " + MAX_LENGTH + " characters.";
        }
        if (!value.matches(".*[A-Z].*")) {
            return "Password must include an uppercase letter.";
        }
        if (!value.matches(".*[a-z].*")) {
            return "Password must include a lowercase letter.";
        }
        if (!value.matches(".*\\d.*")) {
            return "Password must include a digit.";
        }
        if (!value.matches(".*" + SYMBOL_REGEX + ".*")) {
            return "Password must include a symbol.";
        }
        if (BLOCKLIST.contains(value.toLowerCase())) {
            return "Password is too common. Pick something less guessable.";
        }
        return null;
    }

    private static Set<String> loadBlocklist() {
        Set<String> set = new HashSet<>(1024);
        ClassPathResource resource = new ClassPathResource("security/top-1000-passwords.txt");
        try (InputStream in = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim().toLowerCase();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    set.add(trimmed);
                }
            }
            log.info("Loaded {} blocked-password entries", set.size());
        } catch (IOException e) {
            log.warn("Could not load password blocklist; running without it: {}", e.getMessage());
        }
        return set;
    }
}
