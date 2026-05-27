package com.financeapp.dr.store;

import com.financeapp.dr.model.AccountRequest;
import com.financeapp.dr.model.AccountResponse;
import com.financeapp.dr.model.AuditEventResponse;
import com.financeapp.dr.model.PageResult;
import com.financeapp.dr.model.PaymentMethodResponse;
import com.financeapp.dr.model.TransactionResponse;
import com.financeapp.dr.model.TransferResult;
import com.financeapp.dr.model.UserResponse;
import com.financeapp.dr.model.UserWithPasswordHash;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface LedgerStore {

    AccountResponse createAccount(AccountRequest request);

    Optional<AccountResponse> getAccount(String accountId);

    TransactionResponse deposit(String requestId,
                                String accountId,
                                String userId,
                                String username,
                                BigDecimal amount,
                                String description,
                                String paymentMethod,
                                String paymentReference,
                                String region);

    TransactionResponse withdraw(String requestId,
                                 String accountId,
                                 String userId,
                                 String username,
                                 BigDecimal amount,
                                 String description,
                                 String paymentMethod,
                                 String paymentReference,
                                 String region);

    TransferResult transfer(String requestId,
                            String fromAccountId,
                            String fromUserId,
                            String fromUsername,
                            String toUsername,
                            BigDecimal amount,
                            String memo,
                            String region);

    Optional<TransactionResponse> getTransactionById(String transactionId);

    PageResult<TransactionResponse> listTransactionsForAccount(String accountId,
                                                               String cursor,
                                                               int limit);

    UserResponse createUser(String username,
                            String passwordHash,
                            String displayName,
                            String defaultAccountId);

    Optional<UserWithPasswordHash> findUserByUsername(String username);

    List<String> listSeededUsernames();

    PaymentMethodResponse createDemoCard(String userId, String username);

    Optional<PaymentMethodResponse> getDemoCard(String userId);

    Optional<PaymentMethodResponse> getDemoPayPal(String userId);

    PaymentMethodResponse saveDemoPayPal(String userId, String email);

    void logAuditEvent(String userId,
                       String eventType,
                       String ip,
                       String region,
                       String details);

    PageResult<AuditEventResponse> listAuditEventsForUser(String userId,
                                                          String cursor,
                                                          int limit);
}
