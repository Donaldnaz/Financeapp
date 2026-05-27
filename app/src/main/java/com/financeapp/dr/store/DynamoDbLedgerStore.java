package com.financeapp.dr.store;

import com.financeapp.dr.config.AppProperties;
import com.financeapp.dr.model.AccountRequest;
import com.financeapp.dr.model.AccountResponse;
import com.financeapp.dr.model.AuditEventResponse;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static com.financeapp.dr.store.LedgerItemKeys.SK_CARD_DEMO;
import static com.financeapp.dr.store.LedgerItemKeys.SK_METADATA;
import static com.financeapp.dr.store.LedgerItemKeys.SK_PAYPAL_DEMO;
import static com.financeapp.dr.store.LedgerItemKeys.accountPk;
import static com.financeapp.dr.store.LedgerItemKeys.auditUserPk;
import static com.financeapp.dr.store.LedgerItemKeys.idempotencyPk;
import static com.financeapp.dr.store.LedgerItemKeys.parseAccountId;
import static com.financeapp.dr.store.LedgerItemKeys.parseEventId;
import static com.financeapp.dr.store.LedgerItemKeys.parseTransactionId;
import static com.financeapp.dr.store.LedgerItemKeys.parseUsername;
import static com.financeapp.dr.store.LedgerItemKeys.paymentUserPk;
import static com.financeapp.dr.store.LedgerItemKeys.resolveAuditUserId;
import static com.financeapp.dr.store.LedgerItemKeys.transactionGsiPk;
import static com.financeapp.dr.store.LedgerItemKeys.txnSk;
import static com.financeapp.dr.store.LedgerItemKeys.userPk;

@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "dynamodb")
public class DynamoDbLedgerStore implements LedgerStore {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbLedgerStore.class);

    private static final long IDEMPOTENCY_TTL_SECONDS = 86_400L;
    private static final long AUDIT_TTL_SECONDS = 90L * 86_400L;

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoDbLedgerStore(AppProperties properties) {
        this.dynamoDbClient = DynamoDbClient.create();
        this.tableName = properties.storage().tableName();
    }

    @Override
    public AccountResponse createAccount(AccountRequest request) {
        String accountId = UlidCreator.getUlid().toString();
        Instant now = Instant.now();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(accountPk(accountId)));
        item.put("sk", AttributeValue.fromS(SK_METADATA));
        item.put("displayName", AttributeValue.fromS(request.displayName()));
        item.put("currency", AttributeValue.fromS(request.currency()));
        item.put("balance", AttributeValue.fromN("0"));
        item.put("createdAt", AttributeValue.fromS(now.toString()));

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .conditionExpression("attribute_not_exists(pk)")
                .build());

        return new AccountResponse(accountId, request.displayName(), request.currency(),
                BigDecimal.ZERO, now);
    }

    @Override
    public Optional<AccountResponse> getAccount(String accountId) {
        return getAccountInternal(accountId);
    }

    private Optional<AccountResponse> getAccountInternal(String accountId) {
        Map<String, AttributeValue> key = Map.of(
                "pk", AttributeValue.fromS(accountPk(accountId)),
                "sk", AttributeValue.fromS(SK_METADATA)
        );
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .consistentRead(true)
                .build()).item();

        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toAccountResponse(item));
    }

    private AccountResponse toAccountResponse(Map<String, AttributeValue> item) {
        String accountId = item.containsKey("accountId")
                ? item.get("accountId").s()
                : parseAccountId(item.get("pk").s());
        BigDecimal balance = item.containsKey("balance")
                ? new BigDecimal(item.get("balance").n())
                : BigDecimal.ZERO;
        return new AccountResponse(
                accountId,
                item.get("displayName").s(),
                item.get("currency").s(),
                balance,
                Instant.parse(item.get("createdAt").s())
        );
    }

    @Override
    public TransactionResponse deposit(String requestId, String accountId, String userId, String username,
                                       BigDecimal amount, String description,
                                       String paymentMethod, String paymentReference, String region) {
        AccountResponse account = getAccountInternal(accountId)
                .orElseThrow(() -> new NoSuchElementException("Account not found: " + accountId));

        BigDecimal newBalance = account.balance().add(amount);
        String transactionId = UlidCreator.getUlid().toString();
        Instant now = Instant.now();

        Map<String, AttributeValue> idempotencyItem = idempotencyItem(requestId, transactionId, now);
        Map<String, AttributeValue> txnItem = transactionItem(transactionId, accountId, TransactionType.DEPOSIT,
                amount, newBalance, description, region, now,
                username, null, paymentMethod, paymentReference);

        TransactWriteItem idempWrite = TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(idempotencyItem)
                        .conditionExpression("attribute_not_exists(pk)")
                        .build())
                .build();

        TransactWriteItem balanceUpdate = TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "pk", AttributeValue.fromS(accountPk(accountId)),
                                "sk", AttributeValue.fromS(SK_METADATA)))
                        .updateExpression("SET balance = if_not_exists(balance, :zero) + :amt")
                        .expressionAttributeValues(Map.of(
                                ":amt", AttributeValue.fromN(amount.toPlainString()),
                                ":zero", AttributeValue.fromN("0")))
                        .build())
                .build();

        TransactWriteItem txnPut = TransactWriteItem.builder()
                .put(Put.builder().tableName(tableName).item(txnItem).build())
                .build();

        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(idempWrite, balanceUpdate, txnPut)
                    .build());
        } catch (TransactionCanceledException tce) {
            return loadExistingTransactionByRequestId(requestId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "Idempotency conflict but original transaction missing for requestId " + requestId));
        }
        return new TransactionResponse(transactionId, accountId, TransactionType.DEPOSIT, amount, amount, newBalance,
                account.currency(), description, LedgerItemKeys.DEFAULT_STATUS, region, now, userId, username, null, null,
                paymentMethod, paymentReference);
    }

    @Override
    public TransactionResponse withdraw(String requestId, String accountId, String userId, String username,
                                        BigDecimal amount, String description,
                                        String paymentMethod, String paymentReference, String region) {
        AccountResponse account = getAccountInternal(accountId)
                .orElseThrow(() -> new NoSuchElementException("Account not found: " + accountId));

        BigDecimal newBalance = account.balance().subtract(amount);
        String transactionId = UlidCreator.getUlid().toString();
        Instant now = Instant.now();
        BigDecimal signed = amount.negate();

        Map<String, AttributeValue> idempotencyItem = idempotencyItem(requestId, transactionId, now);
        Map<String, AttributeValue> txnItem = transactionItem(transactionId, accountId, TransactionType.WITHDRAWAL,
                signed, newBalance, description, region, now,
                username, null, paymentMethod, paymentReference);

        TransactWriteItem idempWrite = TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(idempotencyItem)
                        .conditionExpression("attribute_not_exists(pk)")
                        .build())
                .build();

        TransactWriteItem balanceUpdate = TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "pk", AttributeValue.fromS(accountPk(accountId)),
                                "sk", AttributeValue.fromS(SK_METADATA)))
                        .updateExpression("SET balance = balance - :amt")
                        .conditionExpression("attribute_exists(balance) AND balance >= :amt")
                        .expressionAttributeValues(Map.of(
                                ":amt", AttributeValue.fromN(amount.toPlainString())))
                        .build())
                .build();

        TransactWriteItem txnPut = TransactWriteItem.builder()
                .put(Put.builder().tableName(tableName).item(txnItem).build())
                .build();

        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(idempWrite, balanceUpdate, txnPut)
                    .build());
        } catch (TransactionCanceledException tce) {
            if (isBalanceCheckFailure(tce, 1)) {
                throw new InsufficientFundsException("Insufficient funds for withdrawal on account " + accountId);
            }
            return loadExistingTransactionByRequestId(requestId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "Idempotency conflict but original transaction missing for requestId " + requestId));
        }
        return new TransactionResponse(transactionId, accountId, TransactionType.WITHDRAWAL, amount, signed, newBalance,
                account.currency(), description, LedgerItemKeys.DEFAULT_STATUS, region, now, userId, username, null, null,
                paymentMethod, paymentReference);
    }

    @Override
    public TransferResult transfer(String requestId, String fromAccountId, String fromUserId, String fromUsername,
                                   String toUsername, BigDecimal amount, String memo, String region) {
        AccountResponse fromAccount = getAccountInternal(fromAccountId)
                .orElseThrow(() -> new NoSuchElementException("Sender account not found: " + fromAccountId));
        UserWithPasswordHash recipient = findUserByUsername(toUsername)
                .orElseThrow(() -> new UserNotFoundException("Recipient not found: " + toUsername));
        String toAccountId = recipient.user().defaultAccountId();
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("Cannot transfer to your own account.");
        }
        AccountResponse toAccount = getAccountInternal(toAccountId)
                .orElseThrow(() -> new NoSuchElementException("Recipient account not found: " + toAccountId));

        BigDecimal senderNewBalance = fromAccount.balance().subtract(amount);
        BigDecimal receiverNewBalance = toAccount.balance().add(amount);

        String outTxnId = UlidCreator.getUlid().toString();
        String inTxnId = UlidCreator.getUlid().toString();
        Instant now = Instant.now();
        BigDecimal signedOut = amount.negate();
        String description = memo == null || memo.isBlank() ? "Transfer" : memo;

        Map<String, AttributeValue> idempotencyItem = idempotencyItem(requestId, outTxnId, now);

        Map<String, AttributeValue> senderTxn = transactionItem(outTxnId, fromAccountId, TransactionType.TRANSFER_OUT,
                signedOut, senderNewBalance, description, region, now,
                fromUsername, toUsername, PaymentMethodType.P2P, toUsername);

        Map<String, AttributeValue> receiverTxn = transactionItem(inTxnId, toAccountId, TransactionType.TRANSFER_IN,
                amount, receiverNewBalance, description, region, now,
                fromUsername, fromUsername, PaymentMethodType.P2P, fromUsername);

        TransactWriteItem idempWrite = TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(idempotencyItem)
                        .conditionExpression("attribute_not_exists(pk)")
                        .build())
                .build();

        TransactWriteItem senderDebit = TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "pk", AttributeValue.fromS(accountPk(fromAccountId)),
                                "sk", AttributeValue.fromS(SK_METADATA)))
                        .updateExpression("SET balance = balance - :amt")
                        .conditionExpression("attribute_exists(balance) AND balance >= :amt")
                        .expressionAttributeValues(Map.of(
                                ":amt", AttributeValue.fromN(amount.toPlainString())))
                        .build())
                .build();

        TransactWriteItem receiverCredit = TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "pk", AttributeValue.fromS(accountPk(toAccountId)),
                                "sk", AttributeValue.fromS(SK_METADATA)))
                        .updateExpression("SET balance = if_not_exists(balance, :zero) + :amt")
                        .expressionAttributeValues(Map.of(
                                ":amt", AttributeValue.fromN(amount.toPlainString()),
                                ":zero", AttributeValue.fromN("0")))
                        .build())
                .build();

        TransactWriteItem senderTxnPut = TransactWriteItem.builder()
                .put(Put.builder().tableName(tableName).item(senderTxn).build())
                .build();

        TransactWriteItem receiverTxnPut = TransactWriteItem.builder()
                .put(Put.builder().tableName(tableName).item(receiverTxn).build())
                .build();

        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(idempWrite, senderDebit, receiverCredit, senderTxnPut, receiverTxnPut)
                    .build());
        } catch (TransactionCanceledException tce) {
            if (isBalanceCheckFailure(tce, 1)) {
                throw new InsufficientFundsException("Insufficient funds for transfer from " + fromAccountId);
            }
            TransferResult existing = loadExistingTransferByRequestId(requestId);
            if (existing != null) {
                return existing;
            }
            throw new NoSuchElementException("Idempotency conflict but original transfer missing for requestId " + requestId);
        }

        TransactionResponse senderResp = new TransactionResponse(outTxnId, fromAccountId, TransactionType.TRANSFER_OUT,
                amount, signedOut, senderNewBalance, fromAccount.currency(), description, LedgerItemKeys.DEFAULT_STATUS,
                region, now, fromUserId, fromUsername, toAccountId, toUsername, PaymentMethodType.P2P, toUsername);
        TransactionResponse receiverResp = new TransactionResponse(inTxnId, toAccountId, TransactionType.TRANSFER_IN,
                amount, amount, receiverNewBalance, toAccount.currency(), description, LedgerItemKeys.DEFAULT_STATUS,
                region, now, fromUserId, fromUsername, fromAccountId, fromUsername, PaymentMethodType.P2P, fromUsername);
        return new TransferResult(senderResp, receiverResp);
    }

    private TransferResult loadExistingTransferByRequestId(String requestId) {
        return loadExistingTransactionByRequestId(requestId)
                .map(senderTxn -> new TransferResult(senderTxn, null))
                .orElse(null);
    }

    private boolean isBalanceCheckFailure(TransactionCanceledException tce, int balanceUpdateIndex) {
        List<CancellationReason> reasons = tce.cancellationReasons();
        if (reasons == null || reasons.size() <= balanceUpdateIndex) {
            return false;
        }
        CancellationReason reason = reasons.get(balanceUpdateIndex);
        return reason != null && "ConditionalCheckFailed".equals(reason.code());
    }

    private Optional<TransactionResponse> loadExistingTransactionByRequestId(String requestId) {
        Map<String, AttributeValue> idempKey = Map.of(
                "pk", AttributeValue.fromS(idempotencyPk(requestId)),
                "sk", AttributeValue.fromS(SK_METADATA)
        );
        Map<String, AttributeValue> idemp = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(idempKey)
                .consistentRead(true)
                .build()).item();
        if (idemp == null || idemp.isEmpty()) {
            return Optional.empty();
        }
        return getTransactionById(idemp.get("transactionId").s());
    }

    @Override
    public Optional<TransactionResponse> getTransactionById(String transactionId) {
        QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .indexName("gsi1-by-transaction-id")
                .keyConditionExpression("gsi1pk = :pk")
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(transactionGsiPk(transactionId))))
                .limit(1)
                .build());

        if (response.items().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toTransactionResponse(response.items().get(0)));
    }

    @Override
    public PageResult<TransactionResponse> listTransactionsForAccount(String accountId, String cursor, int limit) {
        QueryRequest.Builder query = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(accountPk(accountId)),
                        ":prefix", AttributeValue.fromS(LedgerItemKeys.PREFIX_TXN)
                ))
                .scanIndexForward(false)
                .limit(Math.max(1, Math.min(limit, 100)));

        if (cursor != null && !cursor.isBlank()) {
            query.exclusiveStartKey(decodeCursor(cursor));
        }

        QueryResponse response = dynamoDbClient.query(query.build());
        List<TransactionResponse> items = new ArrayList<>(response.items().size());
        for (Map<String, AttributeValue> item : response.items()) {
            items.add(toTransactionResponse(item));
        }
        String nextCursor = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                ? encodeCursor(response.lastEvaluatedKey())
                : null;
        return new PageResult<>(items, nextCursor);
    }

    @Override
    public UserResponse createUser(String username, String passwordHash, String displayName, String defaultAccountId) {
        String userId = UlidCreator.getUlid().toString();
        Instant now = Instant.now();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(userPk(username)));
        item.put("sk", AttributeValue.fromS(SK_METADATA));
        item.put("userId", AttributeValue.fromS(userId));
        item.put("passwordHash", AttributeValue.fromS(passwordHash));
        item.put("displayName", AttributeValue.fromS(displayName));
        item.put("defaultAccountId", AttributeValue.fromS(defaultAccountId));
        item.put("createdAt", AttributeValue.fromS(now.toString()));

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .conditionExpression("attribute_not_exists(pk)")
                .build());

        return new UserResponse(userId, username, displayName, defaultAccountId, now);
    }

    @Override
    public Optional<UserWithPasswordHash> findUserByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> key = Map.of(
                "pk", AttributeValue.fromS(userPk(username)),
                "sk", AttributeValue.fromS(SK_METADATA)
        );
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .consistentRead(true)
                .build()).item();

        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        String resolvedUsername = item.containsKey("username")
                ? item.get("username").s()
                : parseUsername(item.get("pk").s());
        UserResponse user = new UserResponse(
                item.get("userId").s(),
                resolvedUsername,
                item.get("displayName").s(),
                item.get("defaultAccountId").s(),
                Instant.parse(item.get("createdAt").s())
        );
        return Optional.of(new UserWithPasswordHash(user, item.get("passwordHash").s()));
    }

    @Override
    public List<String> listSeededUsernames() {
        return List.of("alice", "bob", "carol", "dave", "eve");
    }

    @Override
    public PaymentMethodResponse createDemoCard(String userId, String username) {
        Optional<PaymentMethodResponse> existing = getDemoCard(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        PaymentMethodResponse card = com.financeapp.dr.model.DemoCardGenerator.create(username);
        Instant now = Instant.now();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(paymentUserPk(userId)));
        item.put("sk", AttributeValue.fromS(SK_CARD_DEMO));
        item.put("type", AttributeValue.fromS(PaymentMethodType.DEMO_CARD));
        item.put("maskedReference", AttributeValue.fromS(card.maskedReference()));
        item.put("linkedAt", AttributeValue.fromS(now.toString()));

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .conditionExpression("attribute_not_exists(pk)")
                .build());

        return new PaymentMethodResponse(card.type(), card.brand(), card.maskedReference(), card.last4(), now);
    }

    @Override
    public Optional<PaymentMethodResponse> getDemoCard(String userId) {
        return getPaymentMethod(userId, SK_CARD_DEMO);
    }

    @Override
    public Optional<PaymentMethodResponse> getDemoPayPal(String userId) {
        return getPaymentMethod(userId, SK_PAYPAL_DEMO);
    }

    @Override
    public PaymentMethodResponse saveDemoPayPal(String userId, String email) {
        Instant now = Instant.now();
        String normalized = email.trim().toLowerCase();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(paymentUserPk(userId)));
        item.put("sk", AttributeValue.fromS(SK_PAYPAL_DEMO));
        item.put("type", AttributeValue.fromS(PaymentMethodType.DEMO_PAYPAL));
        item.put("maskedReference", AttributeValue.fromS(normalized));
        item.put("linkedAt", AttributeValue.fromS(now.toString()));

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());

        return new PaymentMethodResponse(PaymentMethodType.DEMO_PAYPAL, null, normalized, null, now);
    }

    private Optional<PaymentMethodResponse> getPaymentMethod(String userId, String sk) {
        Map<String, AttributeValue> key = Map.of(
                "pk", AttributeValue.fromS(paymentUserPk(userId)),
                "sk", AttributeValue.fromS(sk)
        );
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .consistentRead(true)
                .build()).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toPaymentMethodResponse(item));
    }

    private PaymentMethodResponse toPaymentMethodResponse(Map<String, AttributeValue> item) {
        String type = item.get("type").s();
        String maskedReference = item.get("maskedReference").s();
        String brand = item.containsKey("brand") ? item.get("brand").s() : null;
        String last4 = item.containsKey("last4") ? item.get("last4").s() : null;
        if (PaymentMethodType.DEMO_CARD.equals(type)) {
            if (brand == null) {
                brand = LedgerItemKeys.DEMO_CARD_BRAND;
            }
            if (last4 == null) {
                last4 = LedgerItemKeys.extractLast4FromMaskedReference(maskedReference);
            }
        }
        return new PaymentMethodResponse(
                type,
                brand,
                maskedReference,
                last4,
                Instant.parse(item.get("linkedAt").s())
        );
    }

    @Override
    public void logAuditEvent(String userId, String eventType, String ip, String region, String details) {
        String eventId = UlidCreator.getUlid().toString();
        Instant now = Instant.now();
        long ttl = now.getEpochSecond() + AUDIT_TTL_SECONDS;

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(auditUserPk(resolveAuditUserId(userId))));
        item.put("sk", AttributeValue.fromS(LedgerItemKeys.PREFIX_EVENT + eventId));
        item.put("eventType", AttributeValue.fromS(eventType));
        item.put("region", AttributeValue.fromS(region == null ? "unknown" : region));
        item.put("createdAt", AttributeValue.fromS(now.toString()));
        item.put("ttl", AttributeValue.fromN(Long.toString(ttl)));
        if (ip != null && !ip.isBlank()) {
            item.put("ip", AttributeValue.fromS(ip));
        }
        if (details != null && !details.isBlank()) {
            item.put("details", AttributeValue.fromS(details.length() > 1024 ? details.substring(0, 1024) : details));
        }

        try {
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());
        } catch (RuntimeException ex) {
            log.warn("Audit log write failed for {}: {}", eventType, ex.getMessage());
        }
    }

    @Override
    public PageResult<AuditEventResponse> listAuditEventsForUser(String userId, String cursor, int limit) {
        QueryRequest.Builder query = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(auditUserPk(userId)),
                        ":prefix", AttributeValue.fromS(LedgerItemKeys.PREFIX_EVENT)
                ))
                .scanIndexForward(false)
                .limit(Math.max(1, Math.min(limit, 100)));

        if (cursor != null && !cursor.isBlank()) {
            query.exclusiveStartKey(decodeCursor(cursor));
        }

        QueryResponse response = dynamoDbClient.query(query.build());
        List<AuditEventResponse> items = new ArrayList<>(response.items().size());
        for (Map<String, AttributeValue> item : response.items()) {
            items.add(toAuditEventResponse(item));
        }
        String nextCursor = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                ? encodeCursor(response.lastEvaluatedKey())
                : null;
        return new PageResult<>(items, nextCursor);
    }

    private AuditEventResponse toAuditEventResponse(Map<String, AttributeValue> item) {
        String eventId = item.containsKey("eventId")
                ? item.get("eventId").s()
                : parseEventId(item.get("sk").s());
        return new AuditEventResponse(
                eventId,
                item.get("eventType").s(),
                item.containsKey("region") ? item.get("region").s() : null,
                item.containsKey("ip") ? item.get("ip").s() : null,
                item.containsKey("userAgent") ? item.get("userAgent").s() : null,
                item.containsKey("details") ? item.get("details").s() : null,
                Instant.parse(item.get("createdAt").s())
        );
    }

    private Map<String, AttributeValue> idempotencyItem(String requestId, String transactionId, Instant now) {
        long ttl = now.getEpochSecond() + IDEMPOTENCY_TTL_SECONDS;
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(idempotencyPk(requestId)));
        item.put("sk", AttributeValue.fromS(SK_METADATA));
        item.put("transactionId", AttributeValue.fromS(transactionId));
        item.put("ttl", AttributeValue.fromN(Long.toString(ttl)));
        return item;
    }

    private Map<String, AttributeValue> transactionItem(String transactionId, String accountId, String type,
                                                        BigDecimal signedAmount, BigDecimal balanceAfter,
                                                        String description, String region, Instant now,
                                                        String createdByUsername, String counterpartyUsername,
                                                        String paymentMethod, String paymentReference) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(accountPk(accountId)));
        item.put("sk", AttributeValue.fromS(txnSk(transactionId)));
        item.put("gsi1pk", AttributeValue.fromS(transactionGsiPk(transactionId)));
        item.put("gsi1sk", AttributeValue.fromS(SK_METADATA));
        item.put("type", AttributeValue.fromS(type));
        item.put("signedAmount", AttributeValue.fromN(signedAmount.toPlainString()));
        item.put("balanceAfter", AttributeValue.fromN(balanceAfter.toPlainString()));
        item.put("description", AttributeValue.fromS(description));
        item.put("region", AttributeValue.fromS(region));
        item.put("createdAt", AttributeValue.fromS(now.toString()));
        if (createdByUsername != null) {
            item.put("createdByUsername", AttributeValue.fromS(createdByUsername));
        }
        if (counterpartyUsername != null) {
            item.put("counterpartyUsername", AttributeValue.fromS(counterpartyUsername));
        }
        if (paymentMethod != null) {
            item.put("paymentMethod", AttributeValue.fromS(paymentMethod));
        }
        if (paymentReference != null) {
            item.put("paymentReference", AttributeValue.fromS(paymentReference));
        }
        return item;
    }

    private TransactionResponse toTransactionResponse(Map<String, AttributeValue> item) {
        String transactionId = item.containsKey("transactionId")
                ? item.get("transactionId").s()
                : parseTransactionId(item.get("sk").s());
        String accountId = item.containsKey("accountId")
                ? item.get("accountId").s()
                : parseAccountId(item.get("pk").s());

        BigDecimal signed = item.containsKey("signedAmount")
                ? new BigDecimal(item.get("signedAmount").n())
                : new BigDecimal(item.get("amount").n());
        BigDecimal amount = item.containsKey("amount")
                ? new BigDecimal(item.get("amount").n())
                : signed.abs();
        BigDecimal balanceAfter = item.containsKey("balanceAfter")
                ? new BigDecimal(item.get("balanceAfter").n())
                : null;

        String type = item.containsKey("type") ? item.get("type").s() : TransactionType.DEPOSIT;
        String currency = item.containsKey("currency")
                ? item.get("currency").s()
                : LedgerItemKeys.DEFAULT_CURRENCY;
        String status = item.containsKey("status")
                ? item.get("status").s()
                : LedgerItemKeys.DEFAULT_STATUS;

        return new TransactionResponse(
                transactionId,
                accountId,
                type,
                amount,
                signed,
                balanceAfter,
                currency,
                item.get("description").s(),
                status,
                item.get("region").s(),
                Instant.parse(item.get("createdAt").s()),
                item.containsKey("createdByUserId") ? item.get("createdByUserId").s() : null,
                item.containsKey("createdByUsername") ? item.get("createdByUsername").s() : null,
                item.containsKey("counterpartyAccountId") ? item.get("counterpartyAccountId").s() : null,
                item.containsKey("counterpartyUsername") ? item.get("counterpartyUsername").s() : null,
                item.containsKey("paymentMethod") ? item.get("paymentMethod").s() : null,
                item.containsKey("paymentReference") ? item.get("paymentReference").s() : null
        );
    }

    private static String encodeCursor(Map<String, AttributeValue> key) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, AttributeValue> entry : key.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue().s()).append('"');
        }
        sb.append('}');
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, AttributeValue> decodeCursor(String cursor) {
        String json = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String body = json.trim();
        if (body.startsWith("{")) body = body.substring(1);
        if (body.endsWith("}")) body = body.substring(0, body.length() - 1);
        Map<String, AttributeValue> key = new HashMap<>();
        for (String pair : body.split(",")) {
            String[] kv = pair.split(":", 2);
            String k = kv[0].trim().replaceAll("^\"|\"$", "");
            String v = kv[1].trim().replaceAll("^\"|\"$", "");
            key.put(k, AttributeValue.fromS(v));
        }
        return key;
    }
}
