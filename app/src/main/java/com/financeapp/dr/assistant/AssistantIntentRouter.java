package com.financeapp.dr.assistant;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AssistantIntentRouter {

    private static final Pattern NAVIGATION_PATTERN = Pattern.compile(
            "\\b(?:go\\s+to|open|take\\s+me\\s+to|show\\s+me|navigate\\s+to)\\b");
    private static final Pattern TRANSFER_PATTERN = Pattern.compile(
            "\\b(?:transfer|send|pay)\\b");
    private static final Pattern DEPOSIT_PATTERN = Pattern.compile("\\b(?:deposit|add\\s+money)\\b");
    private static final Pattern WITHDRAW_PATTERN = Pattern.compile("\\b(?:withdraw|cash\\s+out|pull\\s+out)\\b");
    private static final Pattern BALANCE_PATTERN = Pattern.compile(
            "\\b(?:balance|how\\s+much|account\\s+summary|available\\s+funds|checking\\s+account|account\\s+funds|money\\s+do\\s+i\\s+have)\\b");
    private static final Pattern TRANSACTIONS_PATTERN = Pattern.compile(
            "\\b(?:transaction|transactions|activity|recent\\s+activity|history|statement|spending\\s+history|recent\\s+payments|latest\\s+activity)\\b");
    private static final Pattern PAYMENT_METHODS_PATTERN = Pattern.compile(
            "\\b(?:payment\\s+method|payment\\s+methods|demo\\s+card|paypal|linked\\s+card|cards\\s+linked|cards\\s+(?:are\\s+)?on\\s+file|linked\\s+accounts)\\b");
    private static final Pattern AUDIT_PATTERN = Pattern.compile(
            "\\b(?:audit|security\\s+log|login\\s+history|security\\s+events?|recent\\s+logins|security\\s+activity)\\b");

    public RoutedIntent route(String message) {
        if (message == null || message.isBlank()) {
            return new RoutedIntent(AssistantIntent.UNKNOWN, null, null);
        }
        String text = message.toLowerCase(Locale.ROOT).trim();

        if (NAVIGATION_PATTERN.matcher(text).find()) {
            String page = extractNavigationPage(text);
            if (page != null) {
                return new RoutedIntent(AssistantIntent.NAVIGATION, page, null);
            }
        }

        if (text.contains(" page")) {
            String page = extractNavigationPage(text);
            if (page != null) {
                return new RoutedIntent(AssistantIntent.NAVIGATION, page, null);
            }
        }

        if (TRANSFER_PATTERN.matcher(text).find() && !looksLikeNavigationOnly(text)) {
            return new RoutedIntent(AssistantIntent.TRANSFER, null, null);
        }
        if (DEPOSIT_PATTERN.matcher(text).find() && !looksLikeNavigationOnly(text)) {
            return new RoutedIntent(AssistantIntent.DEPOSIT, null, null);
        }
        if (WITHDRAW_PATTERN.matcher(text).find() && !looksLikeNavigationOnly(text)) {
            return new RoutedIntent(AssistantIntent.WITHDRAW, null, null);
        }

        if (BALANCE_PATTERN.matcher(text).find()) {
            return new RoutedIntent(AssistantIntent.BALANCE, null, null);
        }
        if (TRANSACTIONS_PATTERN.matcher(text).find()) {
            return new RoutedIntent(AssistantIntent.TRANSACTIONS, null, extractLimit(text));
        }
        if (PAYMENT_METHODS_PATTERN.matcher(text).find()) {
            return new RoutedIntent(AssistantIntent.PAYMENT_METHODS, null, null);
        }
        if (AUDIT_PATTERN.matcher(text).find()) {
            return new RoutedIntent(AssistantIntent.AUDIT, null, extractLimit(text));
        }

        String standalonePage = extractNavigationPage(text);
        if (standalonePage != null && text.split("\\s+").length <= 4) {
            return new RoutedIntent(AssistantIntent.NAVIGATION, standalonePage, null);
        }

        return new RoutedIntent(AssistantIntent.UNKNOWN, null, null);
    }

    public static boolean isReadOnlyFastPath(AssistantIntent intent) {
        return intent == AssistantIntent.BALANCE
                || intent == AssistantIntent.TRANSACTIONS
                || intent == AssistantIntent.PAYMENT_METHODS
                || intent == AssistantIntent.AUDIT
                || intent == AssistantIntent.NAVIGATION;
    }

    public static boolean isWriteIntent(AssistantIntent intent) {
        return intent == AssistantIntent.TRANSFER
                || intent == AssistantIntent.DEPOSIT
                || intent == AssistantIntent.WITHDRAW;
    }

    private static boolean looksLikeNavigationOnly(String text) {
        return NAVIGATION_PATTERN.matcher(text).find()
                && !text.contains("$")
                && !text.matches(".*\\b(?:to|@)\\s+\\w+.*");
    }

    private static String extractNavigationPage(String text) {
        if (text.contains("transfer") || text.contains("send money") || text.contains("send money page")
                || text.contains("transfer page")) {
            return "transfer";
        }
        if (text.contains("deposit") || text.contains("deposit page") || text.contains("add funds page")) {
            return "deposit";
        }
        if (text.contains("withdraw") || text.contains("withdraw page")) {
            return "withdraw";
        }
        if (text.contains("audit") || text.contains("security")) {
            return "audit";
        }
        if (text.contains("account") || text.contains("activity log")) {
            return "account";
        }
        if (text.contains("dashboard") || text.contains("overview") || text.contains("home")) {
            return "dashboard";
        }
        return null;
    }

    private static Integer extractLimit(String text) {
        Matcher matcher = Pattern.compile("\\b(?:last|recent|show)?\\s*(\\d{1,2})\\b").matcher(text);
        if (matcher.find()) {
            int limit = Integer.parseInt(matcher.group(1));
            if (limit >= 1 && limit <= 10) {
                return limit;
            }
        }
        return null;
    }

    public enum AssistantIntent {
        BALANCE,
        TRANSACTIONS,
        PAYMENT_METHODS,
        AUDIT,
        NAVIGATION,
        TRANSFER,
        DEPOSIT,
        WITHDRAW,
        UNKNOWN
    }

    public record RoutedIntent(AssistantIntent intent, String navigationPage, Integer limit) {
    }
}
