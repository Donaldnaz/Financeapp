package com.financeapp.dr.web;

import com.financeapp.dr.model.AccountRequest;
import com.financeapp.dr.model.AccountResponse;
import com.financeapp.dr.model.AuditEventResponse;
import com.financeapp.dr.model.DepositRequest;
import com.financeapp.dr.model.PageResult;
import com.financeapp.dr.model.SignupRequest;
import com.financeapp.dr.model.TransactionResponse;
import com.financeapp.dr.model.TransferRequest;
import com.financeapp.dr.model.TransferResult;
import com.financeapp.dr.model.UserResponse;
import com.financeapp.dr.model.WithdrawRequest;
import com.financeapp.dr.security.AuthenticatedUser;
import com.financeapp.dr.service.LedgerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private final LedgerService ledgerService;

    public TransactionController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody AccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ledgerService.createAccount(request));
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable("accountId") String accountId,
                                                      @AuthenticationPrincipal AuthenticatedUser user) {
        requireOwner(user, accountId);
        return ResponseEntity.ok(ledgerService.getAccount(accountId));
    }

    @PostMapping("/accounts/{accountId}/deposit")
    public ResponseEntity<TransactionResponse> deposit(
            @PathVariable("accountId") String accountId,
            @RequestHeader(RequestContextFilter.REQUEST_ID_HEADER) String requestId,
            @Valid @RequestBody DepositRequest request,
            @AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest httpRequest) {
        requireOwner(user, accountId);
        TransactionResponse response = ledgerService.deposit(requestId, accountId, user.userId(), user.username(),
                request.amount(), request.descriptionOrDefault(), request.paymentMethodOrDefault(),
                ClientInfo.ip(httpRequest), ClientInfo.userAgent(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/accounts/{accountId}/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(
            @PathVariable("accountId") String accountId,
            @RequestHeader(RequestContextFilter.REQUEST_ID_HEADER) String requestId,
            @Valid @RequestBody WithdrawRequest request,
            @AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest httpRequest) {
        requireOwner(user, accountId);
        TransactionResponse response = ledgerService.withdraw(requestId, accountId, user.userId(), user.username(),
                request.amount(), request.descriptionOrDefault(), request.paypalEmail(),
                ClientInfo.ip(httpRequest), ClientInfo.userAgent(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResult> transfer(
            @RequestHeader(RequestContextFilter.REQUEST_ID_HEADER) String requestId,
            @Valid @RequestBody TransferRequest request,
            @AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest httpRequest) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        TransferResult result = ledgerService.transfer(requestId, user.accountId(), user.userId(), user.username(),
                request.toUsername(), request.amount(), request.memoOrDefault(),
                ClientInfo.ip(httpRequest), ClientInfo.userAgent(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<PageResult<TransactionResponse>> listTransactions(
            @PathVariable("accountId") String accountId,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @AuthenticationPrincipal AuthenticatedUser user) {
        requireOwner(user, accountId);
        return ResponseEntity.ok(ledgerService.listTransactionsForAccount(accountId, cursor, limit));
    }

    @GetMapping("/transactions/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransaction(@PathVariable("transactionId") String transactionId) {
        return ResponseEntity.ok(ledgerService.getTransaction(transactionId));
    }

    @GetMapping("/audit/me")
    public ResponseEntity<PageResult<AuditEventResponse>> myAuditLog(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return ResponseEntity.ok(ledgerService.listAuditEvents(user.userId(), cursor, limit));
    }

    @PostMapping("/signup")
    public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request,
                                               HttpServletRequest httpRequest) {
        if (!request.passwordsMatch()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passwords do not match");
        }
        UserResponse user = ledgerService.signup(request,
                ClientInfo.ip(httpRequest), ClientInfo.userAgent(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return ResponseEntity.ok(Map.of(
                "userId", user.userId(),
                "username", user.username(),
                "displayName", user.displayName(),
                "accountId", user.accountId()
        ));
    }

    private void requireOwner(AuthenticatedUser user, String accountId) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (!accountId.equals(user.accountId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your account");
        }
    }
}
