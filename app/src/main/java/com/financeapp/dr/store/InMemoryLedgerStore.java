package com.financeapp.dr.store;

import com.financeapp.dr.model.AccountRequest;
import com.financeapp.dr.model.AccountResponse;
import com.financeapp.dr.model.AuditEventResponse;
import com.financeapp.dr.model.DemoCardGenerator;
import com.financeapp.dr.model.PageResult;
import com.financeapp.dr.model.PaymentMethodResponse;
import com.financeapp.dr.model.PaymentMethodType;
import com.financeapp.dr.model.TransactionResponse;
import com.financeapp.dr.model.TransactionType;
import com.financeapp.dr.model.TransferResult;
import com.financeapp.dr.model.UserResponse;
import com.financeapp.dr.model.UserWithPasswordHash;
import com.financeapp.dr.service.InsufficientFundsException;
import com.financeapp.dr.service.UserNotFoundException;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryLedgerStore implements LedgerStore {

    private static final String AUDIT_UNKNOWN_USER = "UNKNOWN";

    private final Map<String, AccountState> accounts = new ConcurrentHashMap<>();
    private final Map<String, List<TransactionResponse>> txnsByAccount = new ConcurrentHashMap<>();
    private final Map<String, TransactionResponse> txnsById = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();
    private final Map<String, UserWithPasswordHash> usersByUsername = new ConcurrentHashMap<>();
    private final Map<String, List<AuditEventResponse>> auditsByUser = new ConcurrentHashMap<>();
    private final Map<String, PaymentMethodResponse> demoCards = new ConcurrentHashMap<>();
    private final Map<String, PaymentMethodResponse> demoPayPal = new ConcurrentHashMap<>();

    private static final class AccountState {
        final String accountId;
        final String displayName;
        final String currency;
        final Instant createdAt;
        BigDecimal balance;

        AccountState(String accountId, String displayName, String currency, Instant createdAt) {
            this.accountId = accountId;
            this.displayName = displayName;
            this.currency = currency;
            this.createdAt = createdAt;
            this.balance = BigDecimal.ZERO;
        }

        AccountResponse toResponse() {
            return new AccountResponse(accountId, displayName, currency, balance, createdAt);
        }
    }

    @Override
    public synchronized AccountResponse createAccount(AccountRequest request) {
        String accountId = UlidCreator.getUlid().toString();
        AccountState state = new AccountState(accountId, request.displayName(), request.currency(), Instant.now());
        accounts.put(accountId, state);
        txnsByAccount.put(accountId, new CopyOnWriteArrayList<>());
        return state.toResponse();
    }

    @Override
    public Optional<AccountResponse> getAccount(String accountId) {
        AccountState state = accounts.get(accountId);
        return state == null ? Optional.empty() : Optional.of(state.toResponse());
    }

    @Override
    public synchronized TransactionResponse deposit(String requestId, String accountId, String userId, String username,
                                                    BigDecimal amount, String description,
                                                    String paymentMethod, String paymentReference, String region) {
        String existingTxnId = idempotency.get(requestId);
        if (existingTxnId != null) {
            return txnsById.get(existingTxnId);
        }
        AccountState state = requireAccount(accountId);
        state.balance = state.balance.add(amount);
        TransactionResponse txn = newTxn(accountId, TransactionType.DEPOSIT, amount, amount, state.balance,
                state.currency, description, region, userId, username, null, null,
                paymentMethod, paymentReference);
        recordTxn(requestId, accountId, txn);
        return txn;
    }

    @Override
    public synchronized TransactionResponse withdraw(String requestId, String accountId, String userId, String username,
                                                     BigDecimal amount, String description,
                                                     String paymentMethod, String paymentReference, String region) {
        String existingTxnId = idempotency.get(requestId);
        if (existingTxnId != null) {
            return txnsById.get(existingTxnId);
        }
        AccountState state = requireAccount(accountId);
        if (state.balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient funds on account " + accountId);
        }
        state.balance = state.balance.subtract(amount);
        TransactionResponse txn = newTxn(accountId, TransactionType.WITHDRAWAL, amount, amount.negate(), state.balance,
                state.currency, description, region, userId, username, null, null,
                paymentMethod, paymentReference);
        recordTxn(requestId, accountId, txn);
        return txn;
    }

    @Override
    public synchronized TransferResult transfer(String requestId, String fromAccountId, String fromUserId, String fromUsername,
                                                String toUsername, BigDecimal amount, String memo, String region) {
        String existingTxnId = idempotency.get(requestId);
        if (existingTxnId != null) {
            TransactionResponse senderTxn = txnsById.get(existingTxnId);
            return new TransferResult(senderTxn, null);
        }
        UserWithPasswordHash recipient = findUserByUsername(toUsername)
                .orElseThrow(() -> new UserNotFoundException("Recipient not found: " + toUsername));
        String toAccountId = recipient.user().defaultAccountId();
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("Cannot transfer to your own account.");
        }
        AccountState fromState = requireAccount(fromAccountId);
        AccountState toState = requireAccount(toAccountId);
        if (fromState.balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient funds for transfer from " + fromAccountId);
        }
        fromState.balance = fromState.balance.subtract(amount);
        toState.balance = toState.balance.add(amount);
        String description = memo == null || memo.isBlank() ? "Transfer" : memo;
        TransactionResponse senderTxn = newTxn(fromAccountId, TransactionType.TRANSFER_OUT, amount, amount.negate(),
                fromState.balance, fromState.currency, description, region, fromUserId, fromUsername,
                toAccountId, toUsername, PaymentMethodType.P2P, toUsername);
        TransactionResponse receiverTxn = newTxn(toAccountId, TransactionType.TRANSFER_IN, amount, amount,
                toState.balance, toState.currency, description, region, fromUserId, fromUsername,
                fromAccountId, fromUsername, PaymentMethodType.P2P, fromUsername);
        recordTxn(requestId, fromAccountId, senderTxn);
        txnsById.put(receiverTxn.transactionId(), receiverTxn);
        txnsByAccount.computeIfAbsent(toAccountId, k -> new CopyOnWriteArrayList<>()).add(receiverTxn);
        return new TransferResult(senderTxn, receiverTxn);
    }

    private TransactionResponse newTxn(String accountId, String type, BigDecimal amount, BigDecimal signedAmount,
                                       BigDecimal balanceAfter, String currency, String description, String region,
                                       String userId, String username,
                                       String counterpartyAccountId, String counterpartyUsername,
                                       String paymentMethod, String paymentReference) {
        String transactionId = UlidCreator.getUlid().toString();
        return new TransactionResponse(transactionId, accountId, type, amount, signedAmount, balanceAfter,
                currency, description, "POSTED", region, Instant.now(), userId, username,
                counterpartyAccountId, counterpartyUsername, paymentMethod, paymentReference);
    }

    private void recordTxn(String requestId, String accountId, TransactionResponse txn) {
        idempotency.put(requestId, txn.transactionId());
        txnsById.put(txn.transactionId(), txn);
        txnsByAccount.computeIfAbsent(accountId, k -> new CopyOnWriteArrayList<>()).add(txn);
    }

    private AccountState requireAccount(String accountId) {
        AccountState state = accounts.get(accountId);
        if (state == null) {
            throw new java.util.NoSuchElementException("Account not found: " + accountId);
        }
        return state;
    }

    @Override
    public Optional<TransactionResponse> getTransactionById(String transactionId) {
        return Optional.ofNullable(txnsById.get(transactionId));
    }

    @Override
    public PageResult<TransactionResponse> listTransactionsForAccount(String accountId, String cursor, int limit) {
        List<TransactionResponse> all = new ArrayList<>(txnsByAccount.getOrDefault(accountId, List.of()));
        all.sort(Comparator.comparing(TransactionResponse::transactionId).reversed());
        int start = 0;
        if (cursor != null && !cursor.isBlank()) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).transactionId().equals(cursor)) {
                    start = i + 1;
                    break;
                }
            }
        }
        int end = Math.min(start + Math.max(1, Math.min(limit, 100)), all.size());
        List<TransactionResponse> page = all.subList(start, end);
        String nextCursor = end < all.size() ? page.get(page.size() - 1).transactionId() : null;
        return new PageResult<>(new ArrayList<>(page), nextCursor);
    }

    @Override
    public synchronized UserResponse createUser(String username, String passwordHash, String displayName,
                                                String defaultAccountId) {
        if (usersByUsername.containsKey(username)) {
            throw new IllegalStateException("User already exists: " + username);
        }
        String userId = UlidCreator.getUlid().toString();
        UserResponse user = new UserResponse(userId, username, displayName, defaultAccountId, Instant.now());
        usersByUsername.put(username, new UserWithPasswordHash(user, passwordHash));
        return user;
    }

    @Override
    public Optional<UserWithPasswordHash> findUserByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }

    @Override
    public List<String> listSeededUsernames() {
        return new ArrayList<>(usersByUsername.keySet());
    }

    @Override
    public synchronized PaymentMethodResponse createDemoCard(String userId, String username) {
        PaymentMethodResponse existing = demoCards.get(userId);
        if (existing != null) {
            return existing;
        }
        PaymentMethodResponse card = DemoCardGenerator.create(username);
        demoCards.put(userId, card);
        return card;
    }

    @Override
    public Optional<PaymentMethodResponse> getDemoCard(String userId) {
        return Optional.ofNullable(demoCards.get(userId));
    }

    @Override
    public Optional<PaymentMethodResponse> getDemoPayPal(String userId) {
        return Optional.ofNullable(demoPayPal.get(userId));
    }

    @Override
    public synchronized PaymentMethodResponse saveDemoPayPal(String userId, String email) {
        PaymentMethodResponse paypal = new PaymentMethodResponse(
                PaymentMethodType.DEMO_PAYPAL, null, email.trim().toLowerCase(), null, Instant.now());
        demoPayPal.put(userId, paypal);
        return paypal;
    }

    @Override
    public synchronized void logAuditEvent(String userId, String username, String eventType,
                                           String ip, String userAgent, String region, String details) {
        String key = userId != null && !userId.isBlank() ? userId : AUDIT_UNKNOWN_USER;
        AuditEventResponse event = new AuditEventResponse(
                UlidCreator.getUlid().toString(), eventType, region, ip, userAgent, details, Instant.now());
        auditsByUser.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(event);
    }

    @Override
    public PageResult<AuditEventResponse> listAuditEventsForUser(String userId, String cursor, int limit) {
        List<AuditEventResponse> all = new ArrayList<>(auditsByUser.getOrDefault(userId, List.of()));
        all.sort(Comparator.comparing(AuditEventResponse::eventId).reversed());
        int end = Math.min(Math.max(1, Math.min(limit, 100)), all.size());
        return new PageResult<>(new ArrayList<>(all.subList(0, end)), null);
    }
}
