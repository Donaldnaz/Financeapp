package com.financeapp.dr.assistant.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeapp.dr.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    private final AssistantLlmSettings settings;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OpenAiClient(AppProperties properties,
                        @Qualifier("assistantRestClientBuilder") RestClient.Builder restClientBuilder,
                        ObjectMapper objectMapper) {
        this.settings = AssistantLlmSettings.from(properties);
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return settings.isConfigured();
    }

    public String unavailableMessage() {
        return settings.unavailableMessage();
    }

    public OpenAiCompletionResult complete(List<OpenAiMessage> messages, List<Object> tools) {
        if (!settings.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, settings.unavailableMessage());
        }

        String url = settings.baseUrl() + "/chat/completions";
        OpenAiChatRequest request = new OpenAiChatRequest(
                settings.model(),
                messages,
                tools,
                "auto"
        );

        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request);
            if (settings.requiresAuthHeader()) {
                spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey());
            }
            String body = spec.retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Assistant LLM returned an empty response.");
            }
            OpenAiChatResponse response = objectMapper.readValue(body, OpenAiChatResponse.class);
            return OpenAiCompletionResult.fromResponse(response);
        } catch (RestClientResponseException ex) {
            throw OpenAiErrorMapper.toResponseStatusException(ex, objectMapper, settings);
        } catch (ResourceAccessException ex) {
            log.warn("Assistant LLM request failed (provider={}, model={}): {}",
                    settings.provider(), settings.model(), ex.getMessage());
            if (settings.isOllama()) {
                if (isTimeout(ex)) {
                    throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, ollamaSlowMessage());
                }
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Cannot reach local Ollama at " + settings.baseUrl()
                                + ". Run: ollama serve && ollama pull " + settings.model());
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Assistant LLM is unreachable. Check ASSISTANT_BASE_URL and try again.");
        } catch (JsonProcessingException ex) {
            log.warn("Assistant LLM returned unparsable JSON (provider={}, model={}): {}",
                    settings.provider(), settings.model(), ex.getOriginalMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ollamaOrGenericInvalidResponse());
        } catch (RestClientException ex) {
            log.warn("Assistant LLM client error (provider={}, model={}): {}",
                    settings.provider(), settings.model(), ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ollamaOrGenericInvalidResponse());
        }
    }

    private String ollamaOrGenericInvalidResponse() {
        if (settings.isOllama()) {
            return ollamaSlowMessage();
        }
        return "Assistant LLM returned an invalid response.";
    }

    private String ollamaSlowMessage() {
        return "Local Ollama is too slow or returned an invalid response on this machine. "
                + "Try ASSISTANT_MODEL=llama3.2:3b in .env, restart Ollama (ollama serve), then restart the app.";
    }

    private static boolean isTimeout(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timed out")) {
                return true;
            }
        }
        return false;
    }
}
