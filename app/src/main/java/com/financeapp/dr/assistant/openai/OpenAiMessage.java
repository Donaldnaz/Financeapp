package com.financeapp.dr.assistant.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiMessage(
        String role,
        String content,
        @JsonProperty("tool_calls") List<OpenAiToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId
) {
    public static OpenAiMessage system(String content) {
        return new OpenAiMessage("system", content, null, null);
    }

    public static OpenAiMessage user(String content) {
        return new OpenAiMessage("user", content, null, null);
    }

    public static OpenAiMessage assistant(String content, List<OpenAiToolCall> toolCalls) {
        return new OpenAiMessage("assistant", content, toolCalls, null);
    }

    public static OpenAiMessage tool(String toolCallId, String content) {
        return new OpenAiMessage("tool", content, null, toolCallId);
    }
}
