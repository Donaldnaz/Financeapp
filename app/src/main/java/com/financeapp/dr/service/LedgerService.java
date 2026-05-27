package com.financeapp.dr.service;

import com.financeapp.dr.model.AccountRequest;
import com.financeapp.dr.model.AccountResponse;
import com.financeapp.dr.model.AuditEventResponse;
import com.financeapp.dr.model.PageResult;
import com.financeapp.dr.model.PaymentMethodResponse;
import com.financeapp.dr.model.SignupRequest;
import com.financeapp.dr.model.TransactionResponse;
import com.financeapp.dr.model.TransferResult;
import com.financeapp.dr.model.UserResponse;
import com.financeapp.dr.model.UserWithPasswordHash;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface LedgerService {

    AccountResponse createAccount(AccountRequest request);

    AccountResponse getAccount(String accountId);

    TransactionResponse deposit(String requestId, String accountId, String userId, String username,
                                BigDecimal amount, String description, String paymentMethod,
                                String ip, String userAgent);

    TransactionResponse withdraw(String requestId, String accountId, String userId, String username,
                                 BigDecimal amount, String description, String paypalEmail,
                                 String ip, String userAgent);

    TransferResult transfer(String requestId, String fromAccountId, String fromUserId, String fromUsername,
                            String toUsername, BigDecimal amount, String memo, String ip, String userAgent);

    TransactionResponse getTransaction(String transactionId);

    PageResult<TransactionResponse> listTransactionsForAccount(String accountId, String cursor, int limit);

    UserResponse signup(SignupRequest request, String ip, String userAgent);

    UserResponse signupOAuth(String username, String displayName, String ip, String userAgent);

    UserResponse createUser(String username, String rawPassword, String displayName, String defaultAccountId);

    Optional<UserWithPasswordHash> findUserByUsername(String username);

    List<String> listSeededUsernames();

    Optional<PaymentMethodResponse> getDemoCard(String userId);

    Optional<PaymentMethodResponse> getDemoPayPal(String userId);

    void ensureDemoCard(String userId, String username);

    void recordLoginSuccess(String userId, String username, String ip, String userAgent);

    void recordLoginFailure(String username, String ip, String userAgent, String reason);

    void recordAssistantQuery(String userId, String username, String ip, String userAgent, String summary);

    PageResult<AuditEventResponse> listAuditEvents(String userId, String cursor, int limit);
}
