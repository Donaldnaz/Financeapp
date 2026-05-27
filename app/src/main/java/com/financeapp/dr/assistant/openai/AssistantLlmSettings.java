package com.financeapp.dr.assistant.openai;

import com.financeapp.dr.config.AppProperties;

final class AssistantLlmSettings {

    private final boolean enabled;
    private final String provider;
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    AssistantLlmSettings(boolean enabled, String provider, String apiKey, String model, String baseUrl) {
        this.enabled = enabled;
        this.provider = provider == null ? "ollama" : provider.trim().toLowerCase();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:11434/v1" : baseUrl.trim();
    }

    static AssistantLlmSettings from(AppProperties properties) {
        AppProperties.Assistant assistant = properties == null ? null : properties.assistant();
        if (assistant == null) {
            return new AssistantLlmSettings(false, "ollama", "", "", "http://localhost:11434/v1");
        }
        AppProperties.Assistant.Llm llm = assistant.llm();
        if (llm == null) {
            return new AssistantLlmSettings(assistant.enabled(), assistant.provider(), "", "", "http://localhost:11434/v1");
        }
        return new AssistantLlmSettings(
                assistant.enabled(),
                assistant.provider(),
                llm.apiKey(),
                llm.model(),
                llm.baseUrl()
        );
    }

    boolean isEnabled() {
        return enabled;
    }

    boolean isOllama() {
        return "ollama".equals(provider);
    }

    boolean isOpenAi() {
        return "openai".equals(provider);
    }

    String provider() {
        return provider;
    }

    String apiKey() {
        return apiKey;
    }

    String model() {
        return model;
    }

    String baseUrl() {
        return trimTrailingSlash(baseUrl);
    }

    boolean isConfigured() {
        if (!enabled) {
            return false;
        }
        if (model.isBlank() || baseUrl.isBlank()) {
            return false;
        }
        if (isOllama()) {
            return true;
        }
        return isValidOpenAiKey(apiKey);
    }

    boolean requiresAuthHeader() {
        return isOpenAi() && isValidOpenAiKey(apiKey);
    }

    String unavailableMessage() {
        if (!enabled) {
            return "Assistant is disabled. Set APP_ASSISTANT_ENABLED=true to enable it.";
        }
        if (isOllama()) {
            return "Assistant is unavailable. Start Ollama locally: ollama serve && ollama pull "
                    + (model.isBlank() ? "llama3.1" : model);
        }
        return "Assistant is unavailable. Set ASSISTANT_PROVIDER=openai and a valid ASSISTANT_API_KEY (sk-...) in .env.";
    }

    static boolean isValidOpenAiKey(String key) {
        return key != null && key.startsWith("sk-") && key.length() > 20;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
