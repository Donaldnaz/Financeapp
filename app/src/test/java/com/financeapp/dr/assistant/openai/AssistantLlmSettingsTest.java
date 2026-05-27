package com.financeapp.dr.assistant.openai;

import com.financeapp.dr.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantLlmSettingsTest {

    @Test
    void ollamaIsConfiguredWithModelAndBaseUrlOnly() {
        AssistantLlmSettings settings = AssistantLlmSettings.from(assistant("ollama", "ollama", "llama3.1", "http://localhost:11434/v1"));

        assertThat(settings.isOllama()).isTrue();
        assertThat(settings.isConfigured()).isTrue();
        assertThat(settings.requiresAuthHeader()).isFalse();
    }

    @Test
    void openAiRequiresValidSkKey() {
        AssistantLlmSettings configured = AssistantLlmSettings.from(
                assistant("openai", "sk-proj-123456789012345678901234567890", "gpt-4o-mini", "https://api.openai.com/v1"));
        AssistantLlmSettings missing = AssistantLlmSettings.from(
                assistant("openai", "", "gpt-4o-mini", "https://api.openai.com/v1"));

        assertThat(configured.isOpenAi()).isTrue();
        assertThat(configured.isConfigured()).isTrue();
        assertThat(configured.requiresAuthHeader()).isTrue();
        assertThat(missing.isConfigured()).isFalse();
        assertThat(missing.unavailableMessage()).contains("ASSISTANT_API_KEY");
    }

    @Test
    void disabledAssistantIsNotConfigured() {
        AppProperties.Assistant assistant = new AppProperties.Assistant(
                false,
                "ollama",
                new AppProperties.Assistant.Llm("ollama", "llama3.1", "http://localhost:11434/v1")
        );
        AssistantLlmSettings settings = AssistantLlmSettings.from(new AppProperties("local", null, null, null, assistant));

        assertThat(settings.isConfigured()).isFalse();
    }

    private static AppProperties assistant(String provider, String apiKey, String model, String baseUrl) {
        return new AppProperties(
                "local",
                null,
                null,
                null,
                new AppProperties.Assistant(
                        true,
                        provider,
                        new AppProperties.Assistant.Llm(apiKey, model, baseUrl)
                )
        );
    }
}
