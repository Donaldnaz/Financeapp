package com.financeapp.dr.service;

import com.financeapp.dr.config.AppProperties;
import com.financeapp.dr.model.AccountRequest;
import com.financeapp.dr.model.AccountResponse;
import com.financeapp.dr.model.AuditEventType;
import com.financeapp.dr.model.AuditEventResponse;
import com.financeapp.dr.model.PageResult;
import com.financeapp.dr.model.PaymentMethodResponse;
import com.financeapp.dr.model.PaymentMethodType;
import com.financeapp.dr.model.SignupRequest;
import com.financeapp.dr.model.TransactionResponse;
import com.financeapp.dr.model.TransferResult;
import com.financeapp.dr.model.UserResponse;
import com.financeapp.dr.model.UserWithPasswordHash;
import com.financeapp.dr.store.LedgerStore;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class LedgerServiceImpl implements LedgerService {

    private static final BigDecimal SIGNUP_BONUS = new BigDecimal("1000.00");

    private final LedgerStore store;
    private final PasswordEncoder passwordEncoder;
    private final String region;

    public LedgerServiceImpl(LedgerStore store, PasswordEncoder passwordEncoder, AppProperties appProperties) {
        this.store = store;
        this.passwordEncoder = passwordEncoder;
        this.region = appProperties.region();
    }

    @Override
    public AccountResponse createAccount(AccountRequest request) {
        return store.createAccount(request);
    }

    @Override
    public AccountResponse getAccount(String accountId) {
        return store.getAccount(accountId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Account not found"));
    }

    @Override
    public TransactionResponse deposit(String requestId, String accountId, String userId, String username,
                                       BigDecimal amount, String description, String paymentMethod,
                                       String ip, String userAgent) {
        getAccount(accountId);
        String method = paymentMethod == null || paymentMethod.isBlank()
                ? PaymentMethodType.DEMO_CARD
                : paymentMethod;
        String reference;
        if (PaymentMethodType.WELCOME_BONUS.equals(method)) {
            reference = "Welcome bonus";
        } else {
            PaymentMethodResponse card = store.getDemoCard(userId)
                    .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Demo card not linked to account"));
            reference = card.maskedReference();
        }
        TransactionResponse txn = store.deposit(requestId, accountId, userId, username, amount, description,
                method, reference, region);
        if (PaymentMethodType.DEMO_CARD.equals(method)) {
            store.logAuditEvent(userId, username, AuditEventType.DEPOSIT_CARD, ip, userAgent, region,
                    "amount=" + amount.toPlainString() + " card=" + reference
                            + " balanceAfter=" + nz(txn.balanceAfter()));
        }
        return txn;
    }

    @Override
    public TransactionResponse withdraw(String requestId, String accountId, String userId, String username,
                                        BigDecimal amount, String description, String paypalEmail,
                                        String ip, String userAgent) {
        getAccount(accountId);
        if (paypalEmail == null || paypalEmail.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "PayPal email is required");
        }
        PaymentMethodResponse paypal = store.saveDemoPayPal(userId, paypalEmail);
        TransactionResponse txn = store.withdraw(requestId, accountId, userId, username, amount, description,
                PaymentMethodType.DEMO_PAYPAL, paypal.maskedReference(), region);
        store.logAuditEvent(userId, username, AuditEventType.WITHDRAWAL_PAYPAL, ip, userAgent, region,
                "amount=" + amount.toPlainString() + " paypal=" + paypal.maskedReference()
                        + " balanceAfter=" + nz(txn.balanceAfter()));
        return txn;
    }

    @Override
    public TransferResult transfer(String requestId, String fromAccountId, String fromUserId, String fromUsername,
                                   String toUsername, BigDecimal amount, String memo, String ip, String userAgent) {
        TransferResult result = store.transfer(requestId, fromAccountId, fromUserId, fromUsername,
                toUsername, amount, memo, region);
        store.logAuditEvent(fromUserId, fromUsername, AuditEventType.TRANSFER_OUT, ip, userAgent, region,
                "to=" + toUsername + " amount=" + amount.toPlainString()
                        + " balanceAfter=" + nz(result.senderTransaction().balanceAfter()));
        if (result.receiverTransaction() != null) {
            store.findUserByUsername(toUsername).ifPresent(receiver ->
                    store.logAuditEvent(receiver.user().userId(), receiver.user().username(),
                            AuditEventType.TRANSFER_IN, ip, userAgent, region,
                            "from=" + fromUsername + " amount=" + amount.toPlainString()
                                    + " balanceAfter=" + nz(result.receiverTransaction().balanceAfter())));
        }
        return result;
    }

    @Override
    public TransactionResponse getTransaction(String transactionId) {
        return store.getTransactionById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Transaction not found"));
    }

    @Override
    public PageResult<TransactionResponse> listTransactionsForAccount(String accountId, String cursor, int limit) {
        getAccount(accountId);
        return store.listTransactionsForAccount(accountId, cursor, limit);
    }

    @Override
    public UserResponse signup(SignupRequest request, String ip, String userAgent) {
        if (!request.passwordsMatch()) {
            throw new IllegalArgumentException("Passwords do not match.");
        }
        if (store.findUserByUsername(request.username()).isPresent()) {
            throw new UsernameAlreadyExistsException(request.username());
        }
        AccountResponse account = store.createAccount(
                new AccountRequest(request.displayName() + " Checking", "USD"));
        UserResponse user = createUser(request.username(), request.password(),
                request.displayName(), account.accountId());
        store.createDemoCard(user.userId(), user.username());
        store.deposit(UUID.randomUUID().toString(), account.accountId(),
                user.userId(), user.username(), SIGNUP_BONUS, "Sign-up welcome bonus",
                PaymentMethodType.WELCOME_BONUS, "Welcome bonus", region);
        store.logAuditEvent(user.userId(), user.username(), AuditEventType.SIGNUP, ip, userAgent, region,
                "accountId=" + account.accountId() + " welcomeBonus=" + SIGNUP_BONUS.toPlainString());
        return user;
    }

    @Override
    public UserResponse signupOAuth(String username, String displayName, String ip, String userAgent) {
        Optional<UserWithPasswordHash> existing = store.findUserByUsername(username);
        if (existing.isPresent()) {
            return existing.get().user();
        }
        AccountResponse account = store.createAccount(
                new AccountRequest(displayName + " Checking", "USD"));
        String oauthPassword = UUID.randomUUID() + "Aa1!OAuth";
        UserResponse user = createUser(username, oauthPassword, displayName, account.accountId());
        store.createDemoCard(user.userId(), user.username());
        store.deposit(UUID.randomUUID().toString(), account.accountId(),
                user.userId(), user.username(), SIGNUP_BONUS, "Sign-up welcome bonus",
                PaymentMethodType.WELCOME_BONUS, "Welcome bonus", region);
        store.logAuditEvent(user.userId(), user.username(), AuditEventType.SIGNUP, ip, userAgent, region,
                "accountId=" + account.accountId() + " welcomeBonus=" + SIGNUP_BONUS.toPlainString()
                        + " provider=google");
        return user;
    }

    @Override
    public UserResponse createUser(String username, String rawPassword, String displayName, String defaultAccountId) {
        String hash = passwordEncoder.encode(rawPassword);
        return store.createUser(username, hash, displayName, defaultAccountId);
    }

    @Override
    public Optional<UserWithPasswordHash> findUserByUsername(String username) {
        return store.findUserByUsername(username);
    }

    @Override
    public List<String> listSeededUsernames() {
        return store.listSeededUsernames();
    }

    @Override
    public Optional<PaymentMethodResponse> getDemoCard(String userId) {
        return store.getDemoCard(userId);
    }

    @Override
    public Optional<PaymentMethodResponse> getDemoPayPal(String userId) {
        return store.getDemoPayPal(userId);
    }

    @Override
    public void ensureDemoCard(String userId, String username) {
        store.createDemoCard(userId, username);
    }

    @Override
    public void recordLoginSuccess(String userId, String username, String ip, String userAgent) {
        store.logAuditEvent(userId, username, AuditEventType.LOGIN_SUCCESS, ip, userAgent, region, null);
    }

    @Override
    public void recordLoginFailure(String username, String ip, String userAgent, String reason) {
        String userId = store.findUserByUsername(username)
                .map(u -> u.user().userId())
                .orElse(null);
        store.logAuditEvent(userId, username, AuditEventType.LOGIN_FAILURE, ip, userAgent, region, reason);
    }

    @Override
    public void recordAssistantQuery(String userId, String username, String ip, String userAgent, String summary) {
        store.logAuditEvent(userId, username, AuditEventType.ASSISTANT_QUERY, ip, userAgent, region, summary);
    }

    @Override
    public PageResult<AuditEventResponse> listAuditEvents(String userId, String cursor, int limit) {
        return store.listAuditEventsForUser(userId, cursor, limit);
    }

    private static String nz(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }
}
