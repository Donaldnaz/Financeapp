package com.financeapp.dr.assistant.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiToolCall(
        String id,
        String type,
        OpenAiFunctionCall function
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenAiFunctionCall(String name, String arguments) {
    }
}
