package com.financeapp.dr.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeapp.dr.assistant.model.AssistantLink;
import com.financeapp.dr.assistant.model.AssistantResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class AssistantReplyFormatter {

    private final ObjectMapper objectMapper;

    public AssistantReplyFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AssistantResponse toResponse(String toolName, ToolExecutionResult result) {
        try {
            JsonNode node = objectMapper.readTree(result.content());
            if (node.has("error")) {
                return new AssistantResponse(node.get("error").asText(), result.links(), result.pendingAction());
            }
            String reply = switch (toolName) {
                case "get_account_summary" -> formatAccountSummary(node);
                case "list_recent_transactions" -> formatTransactions(node);
                case "get_payment_methods" -> formatPaymentMethods(node);
                case "list_recent_audit_events" -> formatAuditEvents(node);
                case "suggest_navigation" -> formatNavigation(node);
                case "propose_transfer", "propose_deposit", "propose_withdraw" -> formatProposedAction(node);
                default -> result.content();
            };
            return new AssistantResponse(reply, result.links(), result.pendingAction());
        } catch (Exception ex) {
            return new AssistantResponse(result.content(), result.links(), result.pendingAction());
        }
    }

    private static String formatAccountSummary(JsonNode node) {
        BigDecimal balance = node.path("balance").decimalValue();
        String currency = node.path("currency").asText("USD");
        String masked = node.path("accountIdMasked").asText("****");
        return "Your current balance is " + formatMoney(balance, currency)
                + " (account " + masked + ").";
    }

    private static String formatTransactions(JsonNode node) {
        JsonNode items = node.path("transactions");
        if (!items.isArray() || items.isEmpty()) {
            return "You have no recent transactions.";
        }
        StringBuilder sb = new StringBuilder("Here are your recent transactions:\n");
        int index = 1;
        for (JsonNode txn : items) {
            sb.append(index++).append(". ")
                    .append(txn.path("type").asText("UNKNOWN"))
                    .append(" — ")
                    .append(formatMoney(txn.path("amount").decimalValue(), "USD"));
            String description = txn.path("description").asText(null);
            if (description != null && !description.isBlank()) {
                sb.append(" — ").append(description);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String formatPaymentMethods(JsonNode node) {
        List<String> parts = new ArrayList<>();
        if (node.has("demoCard")) {
            JsonNode card = node.get("demoCard");
            parts.add("Demo card " + card.path("maskedReference").asText()
                    + (card.has("brand") ? " (" + card.path("brand").asText() + ")" : ""));
        }
        if (node.has("demoPayPal")) {
            parts.add("PayPal " + node.path("demoPayPal").path("maskedReference").asText());
        }
        if (parts.isEmpty()) {
            return "No payment methods are linked to your account yet.";
        }
        return "Your linked payment methods: " + String.join("; ", parts) + ".";
    }

    private static String formatAuditEvents(JsonNode node) {
        JsonNode items = node.path("events");
        if (!items.isArray() || items.isEmpty()) {
            return "No recent security events were found.";
        }
        StringBuilder sb = new StringBuilder("Recent security events:\n");
        int index = 1;
        for (JsonNode event : items) {
            sb.append(index++).append(". ")
                    .append(event.path("eventType").asText("EVENT"));
            String details = event.path("details").asText(null);
            if (details != null && !details.isBlank()) {
                sb.append(" — ").append(details);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String formatNavigation(JsonNode node) {
        String label = node.path("label").asText("page");
        return "Opening " + label.toLowerCase(Locale.ROOT) + ".";
    }

    private static String formatProposedAction(JsonNode node) {
        if ("pending_confirmation".equals(node.path("status").asText())) {
            return node.path("summary").asText("Please confirm this action.");
        }
        return node.path("summary").asText("Action prepared.");
    }

    private static String formatMoney(BigDecimal amount, String currency) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.US);
        String formatted = format.format(amount);
        if (!"USD".equalsIgnoreCase(currency)) {
            formatted += " " + currency;
        }
        return formatted;
    }
}
