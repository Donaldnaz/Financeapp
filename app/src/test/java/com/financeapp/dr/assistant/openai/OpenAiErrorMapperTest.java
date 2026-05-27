package com.financeapp.dr.assistant.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiErrorMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsInsufficientQuotaToFriendlyMessage() {
        String body = """
                {"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}
                """;
        var ex = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                null,
                body.getBytes(),
                null
        );
        AssistantLlmSettings settings = new AssistantLlmSettings(
                true, "openai", "sk-test123456789012345678901234", "gpt-4o-mini", "https://api.openai.com/v1");

        ResponseStatusException mapped = OpenAiErrorMapper.toResponseStatusException(ex, objectMapper, settings);

        assertThat(mapped.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(mapped.getReason()).contains("quota exceeded");
    }

    @Test
    void mapsOllama404ToPullModelHint() {
        var ex = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                null,
                "{\"error\":\"model not found\"}".getBytes(),
                null
        );
        AssistantLlmSettings settings = new AssistantLlmSettings(
                true, "ollama", "ollama", "llama3.1", "http://localhost:11434/v1");

        ResponseStatusException mapped = OpenAiErrorMapper.toResponseStatusException(ex, objectMapper, settings);

        assertThat(mapped.getReason()).contains("ollama pull");
    }
}
