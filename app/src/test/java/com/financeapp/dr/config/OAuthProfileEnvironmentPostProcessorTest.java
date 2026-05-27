package com.financeapp.dr.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthProfileEnvironmentPostProcessorTest {

    private final OAuthProfileEnvironmentPostProcessor processor = new OAuthProfileEnvironmentPostProcessor();

    @Test
    void activatesOAuthProfileWhenGoogleClientIdPresent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("GOOGLE_CLIENT_ID", "client-id.apps.googleusercontent.com");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.acceptsProfiles("oauth")).isTrue();
    }

    @Test
    void skipsWhenGoogleClientIdMissing() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.acceptsProfiles("oauth")).isFalse();
    }
}
