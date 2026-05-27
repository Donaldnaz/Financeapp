package com.financeapp.dr.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeapp.dr.assistant.model.AssistantLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantReplyFormatterTest {

    private AssistantReplyFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new AssistantReplyFormatter(new ObjectMapper());
    }

    @Test
    void formatsAccountSummary() {
        var response = formatter.toResponse("get_account_summary", ToolExecutionResult.json("""
                {"accountIdMasked":"****1234","balance":1000.00,"currency":"USD","displayName":"Alice"}
                """));
        assertThat(response.reply()).contains("$1,000.00").contains("****1234");
    }

    @Test
    void formatsNavigationWithLinks() {
        var response = formatter.toResponse("suggest_navigation", new ToolExecutionResult(
                "{\"page\":\"transfer\",\"href\":\"/transfer\",\"label\":\"Send money\"}",
                java.util.List.of(new AssistantLink("Send money", "/transfer")),
                null));
        assertThat(response.reply()).containsIgnoringCase("send money");
        assertThat(response.links()).hasSize(1);
    }

    @Test
    void formatsPendingProposal() {
        var response = formatter.toResponse("propose_transfer", ToolExecutionResult.json("""
                {"status":"pending_confirmation","summary":"Transfer $25.00 to @bob"}
                """));
        assertThat(response.reply()).contains("Transfer $25.00 to @bob");
    }
}
