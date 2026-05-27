package com.financeapp.dr.assistant;

import com.financeapp.dr.assistant.model.AssistantLink;
import com.financeapp.dr.assistant.model.PendingActionResponse;

import java.util.List;

public record ToolExecutionResult(
        String content,
        List<AssistantLink> links,
        PendingActionResponse pendingAction
) {
    public ToolExecutionResult {
        links = links == null ? List.of() : List.copyOf(links);
    }

    public static ToolExecutionResult json(String content) {
        return new ToolExecutionResult(content, List.of(), null);
    }
}
