package com.financeapp.dr.assistant.model;

import java.util.List;

public record AssistantResponse(
        String reply,
        List<AssistantLink> links,
        PendingActionResponse pendingAction
) {
    public AssistantResponse {
        links = links == null ? List.of() : List.copyOf(links);
    }

    public static AssistantResponse of(String reply) {
        return new AssistantResponse(reply, List.of(), null);
    }
}
