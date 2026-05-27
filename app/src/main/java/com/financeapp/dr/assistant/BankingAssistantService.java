package com.financeapp.dr.assistant;

import com.financeapp.dr.assistant.model.AssistantLink;
import com.financeapp.dr.assistant.model.AssistantResponse;
import com.financeapp.dr.assistant.model.ChatMessage;
import com.financeapp.dr.assistant.model.PendingActionResponse;
import com.financeapp.dr.assistant.openai.OpenAiClient;
import com.financeapp.dr.assistant.openai.OpenAiCompletionResult;
import com.financeapp.dr.assistant.openai.OpenAiMessage;
import com.financeapp.dr.assistant.openai.OpenAiToolCall;
import com.financeapp.dr.security.AuthenticatedUser;
import com.financeapp.dr.service.LedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class BankingAssistantService {

    private static final int MAX_HISTORY = 10;

    private static final String SYSTEM_PROMPT = """
            You are the iTrust demo banking assistant. This is a portfolio demo — no real money moves.
            Only discuss the currently authenticated user's account and never ask for passwords.
            For transfers, deposits, or withdrawals always call propose_* tools first and wait for explicit confirmation.
            Never claim money has moved until confirm_pending_action succeeds.
            Prefer suggest_navigation when the user wants to open a banking page.
            Be concise, friendly, and accurate. Use tool results as the source of truth for balances and transactions.
            """;

    private final OpenAiClient openAiClient;
    private final AssistantToolExecutor toolExecutor;
    private final AssistantRateLimiter rateLimiter;
    private final AssistantIntentRouter intentRouter;
    private final AssistantReplyFormatter replyFormatter;
    private final LedgerService ledger;

    public BankingAssistantService(OpenAiClient openAiClient,
                                   AssistantToolExecutor toolExecutor,
                                   AssistantRateLimiter rateLimiter,
                                   AssistantIntentRouter intentRouter,
                                   AssistantReplyFormatter replyFormatter,
                                   LedgerService ledger) {
        this.openAiClient = openAiClient;
        this.toolExecutor = toolExecutor;
        this.rateLimiter = rateLimiter;
        this.intentRouter = intentRouter;
        this.replyFormatter = replyFormatter;
        this.ledger = ledger;
    }

    public AssistantResponse chat(List<ChatMessage> history,
                                  AuthenticatedUser user,
                                  String ip,
                                  String userAgent) {
        ensureAvailable();
        rateLimiter.check(user.userId());

        List<ChatMessage> trimmed = trimHistory(history);
        String latestUserMessage = latestUserMessage(trimmed);
        String summary = latestUserMessage == null ? "(empty)" : latestUserMessage;
        ledger.recordAssistantQuery(user.userId(), user.username(), ip, userAgent, truncate(summary, 200));

        AssistantIntentRouter.RoutedIntent routed = intentRouter.route(latestUserMessage);
        if (AssistantIntentRouter.isReadOnlyFastPath(routed.intent())) {
            DirectToolCall directTool = toDirectToolCall(routed);
            ToolExecutionResult result = toolExecutor.executeByName(
                    directTool.name(), directTool.arguments(), user, ip, userAgent);
            return replyFormatter.toResponse(directTool.name(), result);
        }

        return chatWithLlm(trimmed, routed, user, ip, userAgent);
    }

    public AssistantResponse confirm(String pendingActionId,
                                     AuthenticatedUser user,
                                     String ip,
                                     String userAgent) {
        ensureAvailable();
        rateLimiter.check(user.userId());
        AssistantToolExecutor.AssistantResponsePayload payload =
                toolExecutor.confirmById(pendingActionId, user, ip, userAgent);
        return new AssistantResponse(payload.reply(), payload.links(), payload.pendingAction());
    }

    private AssistantResponse chatWithLlm(List<ChatMessage> trimmed,
                                          AssistantIntentRouter.RoutedIntent routed,
                                          AuthenticatedUser user,
                                          String ip,
                                          String userAgent) {
        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.system(SYSTEM_PROMPT));
        for (ChatMessage message : trimmed) {
            messages.add(toOpenAiMessage(message));
        }

        List<Object> tools = AssistantIntentRouter.isWriteIntent(routed.intent())
                ? AssistantToolDefinitions.writeOnly()
                : AssistantToolDefinitions.readOnly();

        OpenAiCompletionResult result = openAiClient.complete(messages, tools);
        OpenAiMessage assistantMessage = result.choice().message();
        List<OpenAiToolCall> toolCalls = assistantMessage.toolCalls();

        if (toolCalls == null || toolCalls.isEmpty()) {
            String reply = assistantMessage.content();
            if (reply == null || reply.isBlank()) {
                reply = "I'm here to help with your iTrust account.";
            }
            return new AssistantResponse(reply, List.of(), null);
        }

        List<AssistantLink> links = new ArrayList<>();
        PendingActionResponse pendingAction = null;
        String primaryToolName = null;
        ToolExecutionResult primaryResult = null;

        for (OpenAiToolCall toolCall : toolCalls) {
            ToolExecutionResult toolResult = toolExecutor.execute(toolCall, user, ip, userAgent);
            primaryToolName = toolCall.function().name();
            primaryResult = toolResult;
            links.addAll(toolResult.links());
            if (toolResult.pendingAction() != null) {
                pendingAction = toolResult.pendingAction();
            }
        }

        if (primaryToolName != null && primaryResult != null) {
            AssistantResponse formatted = replyFormatter.toResponse(primaryToolName, primaryResult);
            return new AssistantResponse(formatted.reply(), dedupeLinks(links), pendingAction);
        }

        return new AssistantResponse(
                "I need a bit more information to complete that request. Please try again with more detail.",
                dedupeLinks(links),
                pendingAction
        );
    }

    private static DirectToolCall toDirectToolCall(AssistantIntentRouter.RoutedIntent routed) {
        return switch (routed.intent()) {
            case BALANCE -> new DirectToolCall("get_account_summary", "{}");
            case TRANSACTIONS -> new DirectToolCall("list_recent_transactions",
                    routed.limit() == null ? "{}" : "{\"limit\":" + routed.limit() + "}");
            case PAYMENT_METHODS -> new DirectToolCall("get_payment_methods", "{}");
            case AUDIT -> new DirectToolCall("list_recent_audit_events",
                    routed.limit() == null ? "{}" : "{\"limit\":" + routed.limit() + "}");
            case NAVIGATION -> new DirectToolCall("suggest_navigation",
                    "{\"page\":\"" + (routed.navigationPage() == null ? "dashboard" : routed.navigationPage()) + "\"}");
            default -> throw new IllegalStateException("Unsupported fast-path intent: " + routed.intent());
        };
    }

    private void ensureAvailable() {
        if (!openAiClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, openAiClient.unavailableMessage());
        }
    }

    private static List<ChatMessage> trimHistory(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, history.size() - MAX_HISTORY);
        return List.copyOf(history.subList(from, history.size()));
    }

    private static String latestUserMessage(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i);
            if ("user".equalsIgnoreCase(message.role())) {
                return message.content();
            }
        }
        return null;
    }

    private static OpenAiMessage toOpenAiMessage(ChatMessage message) {
        String role = message.role() == null ? "user" : message.role().toLowerCase();
        if (!role.equals("user") && !role.equals("assistant")) {
            role = "user";
        }
        return new OpenAiMessage(role, message.content(), null, null);
    }

    private static List<AssistantLink> dedupeLinks(List<AssistantLink> links) {
        Set<String> seen = new LinkedHashSet<>();
        List<AssistantLink> deduped = new ArrayList<>();
        for (AssistantLink link : links) {
            if (seen.add(link.href())) {
                deduped.add(link);
            }
        }
        return deduped;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record DirectToolCall(String name, String arguments) {
    }
}
