package com.financeapp.dr.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/**
 * Activates the {@code oauth} Spring profile when Google OAuth credentials are present,
 * matching local {@code .env} behaviour without requiring a separate {@code SPRING_PROFILES_ACTIVE} var on ECS.
 */
public class OAuthProfileEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getProperty("GOOGLE_CLIENT_ID") == null) {
            return;
        }
        String clientId = environment.getProperty("GOOGLE_CLIENT_ID", "");
        if (!StringUtils.hasText(clientId)) {
            return;
        }
        if (!environment.acceptsProfiles("oauth")) {
            environment.addActiveProfile("oauth");
        }
    }
}
