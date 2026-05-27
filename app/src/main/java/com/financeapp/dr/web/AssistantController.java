package com.financeapp.dr.web;

import com.financeapp.dr.assistant.BankingAssistantService;
import com.financeapp.dr.assistant.model.AssistantResponse;
import com.financeapp.dr.assistant.model.ChatRequest;
import com.financeapp.dr.assistant.model.ConfirmRequest;
import com.financeapp.dr.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final BankingAssistantService assistantService;

    public AssistantController(BankingAssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/chat")
    public ResponseEntity<AssistantResponse> chat(@Valid @RequestBody ChatRequest request,
                                                  @AuthenticationPrincipal AuthenticatedUser user,
                                                  HttpServletRequest httpRequest) {
        AssistantResponse response = assistantService.chat(
                request.messages(),
                user,
                ClientInfo.ip(httpRequest),
                ClientInfo.userAgent(httpRequest)
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/confirm")
    public ResponseEntity<AssistantResponse> confirm(@Valid @RequestBody ConfirmRequest request,
                                                     @AuthenticationPrincipal AuthenticatedUser user,
                                                     HttpServletRequest httpRequest) {
        AssistantResponse response = assistantService.confirm(
                request.pendingActionId(),
                user,
                ClientInfo.ip(httpRequest),
                ClientInfo.userAgent(httpRequest)
        );
        return ResponseEntity.ok(response);
    }
}
