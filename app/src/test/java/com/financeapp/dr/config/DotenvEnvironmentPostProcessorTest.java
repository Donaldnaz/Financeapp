package com.financeapp.dr.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DotenvEnvironmentPostProcessorTest {

    @Test
    void loadsOnlyAppRelatedKeys() {
        assertThat(DotenvEnvironmentPostProcessor.isAppProperty("ASSISTANT_PROVIDER")).isTrue();
        assertThat(DotenvEnvironmentPostProcessor.isAppProperty("OPENAI_API_KEY")).isTrue();
        assertThat(DotenvEnvironmentPostProcessor.isAppProperty("APP_JWT_SECRET")).isTrue();
        assertThat(DotenvEnvironmentPostProcessor.isAppProperty("GOOGLE_CLIENT_ID")).isTrue();
        assertThat(DotenvEnvironmentPostProcessor.isAppProperty("SPRING_PROFILES_ACTIVE")).isTrue();
        assertThat(DotenvEnvironmentPostProcessor.isAppProperty("AWS_REGION")).isFalse();
        assertThat(DotenvEnvironmentPostProcessor.isAppProperty("ECR_REPO")).isFalse();
        assertThat(DotenvEnvironmentPostProcessor.isAppProperty("TF_VAR_jwt_secret")).isFalse();
    }
}
