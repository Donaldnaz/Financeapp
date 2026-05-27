package com.financeapp.dr.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeapp.dr.assistant.model.AssistantLink;
import com.financeapp.dr.assistant.model.PendingActionResponse;
import com.financeapp.dr.assistant.openai.OpenAiToolCall;
import com.financeapp.dr.model.AccountResponse;
import com.financeapp.dr.model.AuditEventResponse;
import com.financeapp.dr.model.PageResult;
import com.financeapp.dr.model.PaymentMethodResponse;
import com.financeapp.dr.model.PaymentMethodType;
import com.financeapp.dr.model.TransactionResponse;
import com.financeapp.dr.model.TransferResult;
import com.financeapp.dr.security.AuthenticatedUser;
import com.financeapp.dr.service.InsufficientFundsException;
import com.financeapp.dr.service.LedgerService;
import com.financeapp.dr.web.UsernameInput;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class AssistantToolExecutor {

    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");

    private final LedgerService ledger;
    private final PendingActionStore pendingActionStore;
    private final ObjectMapper objectMapper;

    public AssistantToolExecutor(LedgerService ledger,
                                 PendingActionStore pendingActionStore,
                                 ObjectMapper objectMapper) {
        this.ledger = ledger;
        this.pendingActionStore = pendingActionStore;
        this.objectMapper = objectMapper;
    }

    public ToolExecutionResult execute(OpenAiToolCall toolCall,
                                       AuthenticatedUser user,
                                       String ip,
                                       String userAgent) {
        return executeByName(toolCall.function().name(), toolCall.function().arguments(), user, ip, userAgent);
    }

    public ToolExecutionResult executeByName(String name,
                                             String arguments,
                                             AuthenticatedUser user,
                                             String ip,
                                             String userAgent) {
        JsonNode args = parseArgs(arguments);
        try {
            return switch (name) {
                case "get_account_summary" -> getAccountSummary(user);
                case "list_recent_transactions" -> listRecentTransactions(user, intArg(args, "limit", 5));
                case "get_payment_methods" -> getPaymentMethods(user);
                case "list_recent_audit_events" -> listRecentAuditEvents(user, intArg(args, "limit", 5));
                case "suggest_navigation" -> suggestNavigation(user, textArg(args, "page"));
                case "propose_transfer" -> proposeTransfer(user, args);
                case "propose_deposit" -> proposeDeposit(user, args);
                case "propose_withdraw" -> proposeWithdraw(user, args);
                case "confirm_pending_action" -> confirmPendingAction(user, textArg(args, "pendingActionId"), ip, userAgent);
                default -> ToolExecutionResult.json("{\"error\":\"Unknown tool: " + name + "\"}");
            };
        } catch (IllegalArgumentException ex) {
            return ToolExecutionResult.json("{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
        }
    }

    public AssistantResponsePayload confirmById(String pendingActionId,
                                                AuthenticatedUser user,
                                                String ip,
                                                String userAgent) {
        ToolExecutionResult result = confirmPendingAction(user, pendingActionId, ip, userAgent);
        return new AssistantResponsePayload(formatConfirmReply(result.content()), result.links(), result.pendingAction());
    }

    private String formatConfirmReply(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.has("error")) {
                return node.get("error").asText();
            }
            if ("completed".equals(node.path("status").asText())) {
                String type = node.path("type").asText();
                BigDecimal amount = node.has("amount") ? node.get("amount").decimalValue() : null;
                return switch (type) {
                    case "TRANSFER" -> "Transfer of $" + amount.toPlainString()
                            + " to @" + node.path("toUsername").asText()
                            + " completed. New balance: $" + node.path("senderBalanceAfter").decimalValue().toPlainString() + ".";
                    case "DEPOSIT" -> "Deposit of $" + amount.toPlainString()
                            + " completed. New balance: $" + node.path("balanceAfter").decimalValue().toPlainString() + ".";
                    case "WITHDRAW" -> "Withdrawal of $" + amount.toPlainString()
                            + " to " + node.path("paypalEmail").asText()
                            + " completed. New balance: $" + node.path("balanceAfter").decimalValue().toPlainString() + ".";
                    default -> "Action completed successfully.";
                };
            }
        } catch (Exception ignored) {
            // fall through
        }
        return json;
    }

    private ToolExecutionResult getAccountSummary(AuthenticatedUser user) {
        AccountResponse account = ledger.getAccount(user.accountId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountIdMasked", maskAccountId(account.accountId()));
        payload.put("balance", account.balance());
        payload.put("currency", account.currency());
        payload.put("displayName", account.displayName());
        return ToolExecutionResult.json(toJson(payload));
    }

    private ToolExecutionResult listRecentTransactions(AuthenticatedUser user, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 10);
        PageResult<TransactionResponse> page = ledger.listTransactionsForAccount(user.accountId(), null, safeLimit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (TransactionResponse txn : page.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", txn.type());
            row.put("amount", txn.amount());
            row.put("balanceAfter", txn.balanceAfter());
            row.put("description", txn.description());
            row.put("paymentMethod", txn.paymentMethod());
            row.put("createdAt", txn.createdAt());
            items.add(row);
        }
        return ToolExecutionResult.json(toJson(Map.of("transactions", items)));
    }

    private ToolExecutionResult getPaymentMethods(AuthenticatedUser user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        ledger.getDemoCard(user.userId()).ifPresent(card -> payload.put("demoCard", paymentMethod(card)));
        ledger.getDemoPayPal(user.userId()).ifPresent(paypal -> payload.put("demoPayPal", paymentMethod(paypal)));
        return ToolExecutionResult.json(toJson(payload));
    }

    private ToolExecutionResult listRecentAuditEvents(AuthenticatedUser user, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 10);
        PageResult<AuditEventResponse> page = ledger.listAuditEvents(user.userId(), null, safeLimit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AuditEventResponse event : page.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventType", event.eventType());
            row.put("details", event.details());
            row.put("region", event.region());
            row.put("createdAt", event.createdAt());
            items.add(row);
        }
        return ToolExecutionResult.json(toJson(Map.of("events", items)));
    }

    private ToolExecutionResult suggestNavigation(AuthenticatedUser user, String page) {
        String normalized = page == null ? "dashboard" : page.toLowerCase(Locale.ROOT);
        AssistantLink link = switch (normalized) {
            case "transfer" -> new AssistantLink("Send money", "/transfer");
            case "deposit" -> new AssistantLink("Deposit", "/deposit");
            case "withdraw" -> new AssistantLink("Withdraw", "/withdraw");
            case "audit" -> new AssistantLink("Security log", "/audit");
            case "account" -> new AssistantLink("Account activity", "/accounts/" + user.accountId());
            default -> new AssistantLink("Overview", "/dashboard");
        };
        Map<String, Object> payload = Map.of(
                "page", normalized,
                "href", link.href(),
                "label", link.label()
        );
        return new ToolExecutionResult(toJson(payload), List.of(link), null);
    }

    private ToolExecutionResult proposeTransfer(AuthenticatedUser user, JsonNode args) {
        String toUsername = requireUsername(textArg(args, "toUsername"));
        BigDecimal amount = requireAmount(decimalArg(args, "amount"));
        String memo = textArg(args, "memo");
        if (toUsername.equalsIgnoreCase(user.username())) {
            return ToolExecutionResult.json("{\"error\":\"You cannot transfer to yourself.\"}");
        }
        ledger.findUserByUsername(toUsername)
                .orElseThrow(() -> new IllegalArgumentException("Recipient '" + toUsername + "' was not found."));
        PendingAction action = storePending(user, "TRANSFER",
                "Transfer $" + amount.toPlainString() + " to @" + toUsername,
                toUsername, amount, memo == null ? "Assistant transfer" : memo, null);
        return pendingResult(action);
    }

    private ToolExecutionResult proposeDeposit(AuthenticatedUser user, JsonNode args) {
        BigDecimal amount = requireAmount(decimalArg(args, "amount"));
        String description = textArg(args, "description");
        PendingAction action = storePending(user, "DEPOSIT",
                "Deposit $" + amount.toPlainString() + " from demo card",
                null, amount, description == null ? "Assistant deposit" : description, null);
        return pendingResult(action);
    }

    private ToolExecutionResult proposeWithdraw(AuthenticatedUser user, JsonNode args) {
        BigDecimal amount = requireAmount(decimalArg(args, "amount"));
        String paypalEmail = textArg(args, "paypalEmail");
        if (paypalEmail == null || paypalEmail.isBlank() || !paypalEmail.contains("@")) {
            return ToolExecutionResult.json("{\"error\":\"A valid PayPal email is required.\"}");
        }
        String description = textArg(args, "description");
        PendingAction action = storePending(user, "WITHDRAW",
                "Withdraw $" + amount.toPlainString() + " to PayPal " + paypalEmail.trim(),
                null, amount, description == null ? "Assistant withdrawal" : description, paypalEmail.trim());
        return pendingResult(action);
    }

    private ToolExecutionResult confirmPendingAction(AuthenticatedUser user,
                                                     String pendingActionId,
                                                     String ip,
                                                     String userAgent) {
        if (pendingActionId == null || pendingActionId.isBlank()) {
            return ToolExecutionResult.json("{\"error\":\"pendingActionId is required.\"}");
        }
        Optional<PendingAction> pendingOpt = pendingActionStore.getForUser(pendingActionId, user.userId());
        if (pendingOpt.isEmpty()) {
            return ToolExecutionResult.json("{\"error\":\"Pending action not found or expired.\"}");
        }
        PendingAction pending = pendingOpt.get();
        try {
            Map<String, Object> result = switch (pending.type()) {
                case "TRANSFER" -> executeTransfer(user, pending, ip, userAgent);
                case "DEPOSIT" -> executeDeposit(user, pending, ip, userAgent);
                case "WITHDRAW" -> executeWithdraw(user, pending, ip, userAgent);
                default -> Map.of("error", "Unsupported pending action type");
            };
            pendingActionStore.remove(pending.id());
            return ToolExecutionResult.json(toJson(result));
        } catch (InsufficientFundsException ex) {
            return ToolExecutionResult.json("{\"error\":\"Insufficient funds for this action.\"}");
        } catch (RuntimeException ex) {
            return ToolExecutionResult.json("{\"error\":\"" + ex.getMessage() + "\"}");
        }
    }

    private Map<String, Object> executeTransfer(AuthenticatedUser user, PendingAction pending, String ip, String userAgent) {
        TransferResult result = ledger.transfer(
                UUID.randomUUID().toString(),
                user.accountId(),
                user.userId(),
                user.username(),
                pending.toUsername(),
                pending.amount(),
                pending.memo(),
                ip,
                userAgent
        );
        return Map.of(
                "status", "completed",
                "type", "TRANSFER",
                "amount", pending.amount(),
                "toUsername", pending.toUsername(),
                "senderBalanceAfter", result.senderTransaction().balanceAfter()
        );
    }

    private Map<String, Object> executeDeposit(AuthenticatedUser user, PendingAction pending, String ip, String userAgent) {
        TransactionResponse txn = ledger.deposit(
                UUID.randomUUID().toString(),
                user.accountId(),
                user.userId(),
                user.username(),
                pending.amount(),
                pending.memo(),
                PaymentMethodType.DEMO_CARD,
                ip,
                userAgent
        );
        return Map.of(
                "status", "completed",
                "type", "DEPOSIT",
                "amount", pending.amount(),
                "balanceAfter", txn.balanceAfter()
        );
    }

    private Map<String, Object> executeWithdraw(AuthenticatedUser user, PendingAction pending, String ip, String userAgent) {
        TransactionResponse txn = ledger.withdraw(
                UUID.randomUUID().toString(),
                user.accountId(),
                user.userId(),
                user.username(),
                pending.amount(),
                pending.memo(),
                pending.paypalEmail(),
                ip,
                userAgent
        );
        return Map.of(
                "status", "completed",
                "type", "WITHDRAW",
                "amount", pending.amount(),
                "balanceAfter", txn.balanceAfter(),
                "paypalEmail", pending.paypalEmail()
        );
    }

    private PendingAction storePending(AuthenticatedUser user,
                                       String type,
                                       String summary,
                                       String toUsername,
                                       BigDecimal amount,
                                       String memo,
                                       String paypalEmail) {
        PendingAction action = new PendingAction(
                UlidCreator.getUlid().toString(),
                user.userId(),
                type,
                summary,
                PendingActionStore.defaultExpiry(),
                toUsername,
                amount,
                memo,
                paypalEmail
        );
        return pendingActionStore.save(action);
    }

    private ToolExecutionResult pendingResult(PendingAction action) {
        PendingActionResponse response = new PendingActionResponse(action.id(), action.summary(), action.expiresAt());
        Map<String, Object> payload = Map.of(
                "status", "pending_confirmation",
                "pendingActionId", action.id(),
                "summary", action.summary(),
                "expiresAt", action.expiresAt()
        );
        return new ToolExecutionResult(toJson(payload), List.of(), response);
    }

    private static Map<String, Object> paymentMethod(PaymentMethodResponse method) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", method.type());
        map.put("maskedReference", method.maskedReference());
        if (method.brand() != null) {
            map.put("brand", method.brand());
        }
        return map;
    }

    private static String maskAccountId(String accountId) {
        if (accountId == null || accountId.length() < 8) {
            return "****";
        }
        return "****" + accountId.substring(accountId.length() - 4);
    }

    private static String requireUsername(String username) {
        String normalized = UsernameInput.normalizeRecipient(username);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("toUsername is required.");
        }
        return normalized;
    }

    private static BigDecimal requireAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(MIN_AMOUNT) < 0) {
            throw new IllegalArgumentException("Amount must be at least 0.01.");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private JsonNode parseArgs(String arguments) {
        try {
            if (arguments == null || arguments.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(arguments);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid tool arguments.");
        }
    }

    private static String textArg(JsonNode args, String field) {
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static int intArg(JsonNode args, String field, int defaultValue) {
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? defaultValue : node.asInt(defaultValue);
    }

    private static BigDecimal decimalArg(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        return new BigDecimal(node.asText());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{\"error\":\"Failed to serialize tool result.\"}";
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record AssistantResponsePayload(String reply, List<AssistantLink> links, PendingActionResponse pendingAction) {
    }
}
