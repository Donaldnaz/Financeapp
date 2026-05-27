package com.financeapp.dr.assistant.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiCompletionResult(OpenAiChoice choice) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenAiChoice(OpenAiMessage message) {
    }

    public static OpenAiCompletionResult fromResponse(OpenAiChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("OpenAI returned no choices");
        }
        return new OpenAiCompletionResult(response.choices().getFirst());
    }
}
