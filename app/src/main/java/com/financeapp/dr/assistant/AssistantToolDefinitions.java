package com.financeapp.dr.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AssistantToolDefinitions {

    private AssistantToolDefinitions() {
    }

    public static List<Object> readOnly() {
        return List.of(
                function("get_account_summary", "Get the logged-in user's account balance and masked account id", Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "required", List.of()
                )),
                function("list_recent_transactions", "List recent transactions for the user's account", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "limit", Map.of("type", "integer", "description", "Max items, default 5, max 10")
                        ),
                        "required", List.of()
                )),
                function("get_payment_methods", "Get linked demo card and PayPal payment methods", Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "required", List.of()
                )),
                function("list_recent_audit_events", "List recent security audit events for the user", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "limit", Map.of("type", "integer", "description", "Max items, default 5, max 10")
                        ),
                        "required", List.of()
                )),
                function("suggest_navigation", "Return in-app navigation links for banking pages", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "page", Map.of(
                                        "type", "string",
                                        "enum", List.of("dashboard", "transfer", "deposit", "withdraw", "audit", "account"),
                                        "description", "Which page to link to"
                                )
                        ),
                        "required", List.of("page")
                ))
        );
    }

    public static List<Object> writeOnly() {
        return List.of(
                function("propose_transfer", "Propose a peer-to-peer transfer requiring user confirmation", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "toUsername", Map.of("type", "string"),
                                "amount", Map.of("type", "number"),
                                "memo", Map.of("type", "string")
                        ),
                        "required", List.of("toUsername", "amount")
                )),
                function("propose_deposit", "Propose a demo card deposit requiring user confirmation", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "amount", Map.of("type", "number"),
                                "description", Map.of("type", "string")
                        ),
                        "required", List.of("amount")
                )),
                function("propose_withdraw", "Propose a demo PayPal withdrawal requiring user confirmation", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "amount", Map.of("type", "number"),
                                "paypalEmail", Map.of("type", "string"),
                                "description", Map.of("type", "string")
                        ),
                        "required", List.of("amount", "paypalEmail")
                )),
                function("confirm_pending_action", "Confirm a previously proposed banking action by pendingActionId", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "pendingActionId", Map.of("type", "string")
                        ),
                        "required", List.of("pendingActionId")
                ))
        );
    }

    public static List<Object> all() {
        List<Object> tools = new java.util.ArrayList<>(readOnly());
        tools.addAll(writeOnly());
        return List.copyOf(tools);
    }

    private static Map<String, Object> function(String name, String description, Map<String, Object> parameters) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", parameters);
        tool.put("function", fn);
        return tool;
    }
}
