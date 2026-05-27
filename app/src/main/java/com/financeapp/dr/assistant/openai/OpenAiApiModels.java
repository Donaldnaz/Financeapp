package com.financeapp.dr.assistant.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAiChatResponse(List<OpenAiCompletionResult.OpenAiChoice> choices) {
}

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAiChatRequest(
        String model,
        List<OpenAiMessage> messages,
        List<Object> tools,
        @JsonProperty("tool_choice") String toolChoice
) {
}
