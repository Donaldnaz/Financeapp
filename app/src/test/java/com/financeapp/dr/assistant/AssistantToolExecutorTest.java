package com.financeapp.dr.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeapp.dr.assistant.model.AssistantLink;
import com.financeapp.dr.assistant.openai.OpenAiToolCall;
import com.financeapp.dr.model.UserResponse;
import com.financeapp.dr.model.UserWithPasswordHash;
import com.financeapp.dr.security.AuthenticatedUser;
import com.financeapp.dr.service.InsufficientFundsException;
import com.financeapp.dr.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantToolExecutorTest {

    @Mock
    private LedgerService ledger;

    private AssistantToolExecutor executor;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        executor = new AssistantToolExecutor(ledger, new PendingActionStore(), new ObjectMapper());
        user = new AuthenticatedUser("user-1", "alice", "Alice", "acct-1");
    }

    @Test
    void proposeTransferRejectsNegativeAmount() {
        ToolExecutionResult result = executor.execute(toolCall("propose_transfer",
                "{\"toUsername\":\"bob\",\"amount\":-5}"), user, "127.0.0.1", "test");
        assertThat(result.content()).contains("Amount must be at least");
    }

    @Test
    void proposeTransferRejectsSelfTransfer() {
        ToolExecutionResult result = executor.execute(toolCall("propose_transfer",
                "{\"toUsername\":\"alice\",\"amount\":10}"), user, "127.0.0.1", "test");
        assertThat(result.content()).contains("cannot transfer to yourself");
    }

    @Test
    void confirmTransferFailsWhenInsufficientFunds() {
        when(ledger.findUserByUsername("bob")).thenReturn(Optional.of(
                new UserWithPasswordHash(
                        new UserResponse("user-2", "bob", "Bob", "acct-2", Instant.now()),
                        "hash")));

        ToolExecutionResult proposed = executor.execute(toolCall("propose_transfer",
                "{\"toUsername\":\"bob\",\"amount\":10}"), user, "127.0.0.1", "test");
        String pendingActionId = proposed.pendingAction().id();

        when(ledger.transfer(any(), eq("acct-1"), eq("user-1"), eq("alice"), eq("bob"),
                eq(new BigDecimal("10.00")), any(), any(), any()))
                .thenThrow(new InsufficientFundsException("Insufficient funds"));

        ToolExecutionResult confirm = executor.execute(toolCall("confirm_pending_action",
                "{\"pendingActionId\":\"" + pendingActionId + "\"}"), user, "127.0.0.1", "test");
        assertThat(confirm.content()).contains("Insufficient funds");
    }

    @Test
    void suggestNavigationUsesAuthenticatedAccountId() {
        ToolExecutionResult result = executor.execute(toolCall("suggest_navigation", "{\"page\":\"account\"}"),
                user, "127.0.0.1", "test");
        assertThat(result.links()).containsExactly(new AssistantLink("Account activity", "/accounts/acct-1"));
    }

    private static OpenAiToolCall toolCall(String name, String arguments) {
        return new OpenAiToolCall("call-1", "function", new OpenAiToolCall.OpenAiFunctionCall(name, arguments));
    }
}
