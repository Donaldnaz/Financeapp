package com.financeapp.dr.assistant.openai;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class OpenAiErrorMapper {

    private OpenAiErrorMapper() {
    }

    static ResponseStatusException toResponseStatusException(RestClientResponseException ex,
                                                           ObjectMapper objectMapper,
                                                           AssistantLlmSettings settings) {
        int statusCode = ex.getStatusCode().value();
        ParsedError parsed = parse(ex.getResponseBodyAsString(), objectMapper);
        HttpStatus status = mapStatus(statusCode, parsed.code());
        String message = friendlyMessage(statusCode, parsed, settings);
        return new ResponseStatusException(status, message);
    }

    private static ParsedError parse(String body, ObjectMapper objectMapper) {
        if (body == null || body.isBlank()) {
            return new ParsedError(null, null);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            return new ParsedError(
                    textOrNull(error.path("code")),
                    textOrNull(error.path("message"))
            );
        } catch (Exception ignored) {
            return new ParsedError(null, body.trim());
        }
    }

    private static HttpStatus mapStatus(int statusCode, String code) {
        if (statusCode == 401) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (statusCode == 429) {
            return "insufficient_quota".equals(code) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.TOO_MANY_REQUESTS;
        }
        if (statusCode >= 500) {
            return HttpStatus.BAD_GATEWAY;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    private static String friendlyMessage(int statusCode, ParsedError parsed, AssistantLlmSettings settings) {
        if (settings.isOllama()) {
            if (statusCode == 404) {
                return "Ollama model '" + settings.model() + "' is not installed. Run: ollama pull "
                        + settings.model();
            }
            return "Local Ollama request failed. Ensure Ollama is running (ollama serve) and model "
                    + settings.model() + " is pulled.";
        }
        if ("insufficient_quota".equals(parsed.code())) {
            return "OpenAI quota exceeded. Add billing or credits at platform.openai.com, then try again.";
        }
        if (statusCode == 401) {
            return "Invalid OpenAI API key. Check ASSISTANT_API_KEY and try again.";
        }
        if (statusCode == 429) {
            return "OpenAI rate limit reached. Wait a moment and try again.";
        }
        if (parsed.message() != null && !parsed.message().isBlank()) {
            return parsed.message();
        }
        return "Assistant request to the configured LLM failed. Try again in a moment.";
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private record ParsedError(String code, String message) {
    }
}
