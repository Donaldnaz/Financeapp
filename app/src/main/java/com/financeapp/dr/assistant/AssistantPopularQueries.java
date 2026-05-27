package com.financeapp.dr.assistant;

import java.util.List;

public final class AssistantPopularQueries {

    private AssistantPopularQueries() {
    }

    public record PopularQuery(String label, String message) {
    }

    public static List<PopularQuery> all() {
        return List.of(
                new PopularQuery("Balance", "What's my balance?"),
                new PopularQuery("Activity", "Show my recent transactions"),
                new PopularQuery("Cards", "What payment methods do I have?"),
                new PopularQuery("Send money", "Take me to transfer"),
                new PopularQuery("Security", "Show my security log"),
                new PopularQuery("Deposit", "Go to deposit")
        );
    }
}
