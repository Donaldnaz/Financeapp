package com.financeapp.dr.assistant.model;

import java.util.List;

public record ChatRequest(List<ChatMessage> messages) {
}
