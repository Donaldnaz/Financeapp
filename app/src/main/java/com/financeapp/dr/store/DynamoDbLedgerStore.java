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

@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "dynamodb")
public class DynamoDbLedgerStore implements LedgerStore {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbLedgerStore.class);

    private static final String ENTITY_ACCOUNT = "ACCOUNT";
    private static final String ENTITY_TRANSACTION = "TRANSACTION";
    private static final String ENTITY_IDEMPOTENCY = "IDEMPOTENCY";
    private static final String ENTITY_USER = "USER";
    private static final String ENTITY_AUDIT = "AUDIT";
    private static final String SK_METADATA = "METADATA";
    private static final long IDEMPOTENCY_TTL_SECONDS = 86_400L;
    private static final String ENTITY_PAYMENT = "PAYMENT";
    private static final String SK_CARD_DEMO = "CARD#DEMO";
    private static final String SK_PAYPAL_DEMO = "PAYPAL#DEMO";
    private static final String AUDIT_UNKNOWN_USER = "UNKNOWN";
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
        item.put("entityType", AttributeValue.fromS(ENTITY_ACCOUNT));
        item.put("accountId", AttributeValue.fromS(accountId));
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
        BigDecimal balance = item.containsKey("balance")
                ? new BigDecimal(item.get("balance").n())
                : BigDecimal.ZERO;
        return new AccountResponse(
                item.get("accountId").s(),
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

        Map<String, AttributeValue> idempotencyItem = idempotencyItem(requestId, transactionId, accountId, now);
        Map<String, AttributeValue> txnItem = transactionItem(transactionId, accountId, TransactionType.DEPOSIT,
                amount, amount, newBalance, account.currency(), description, region, now,
                userId, username, null, null, requestId, paymentMethod, paymentReference);

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
                account.currency(), description, "POSTED", region, now, userId, username, null, null,
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

        Map<String, AttributeValue> idempotencyItem = idempotencyItem(requestId, transactionId, accountId, now);
        Map<String, AttributeValue> txnItem = transactionItem(transactionId, accountId, TransactionType.WITHDRAWAL,
                amount, signed, newBalance, account.currency(), description, region, now,
                userId, username, null, null, requestId, paymentMethod, paymentReference);

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
                account.currency(), description, "POSTED", region, now, userId, username, null, null,
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

        Map<String, AttributeValue> idempotencyItem = idempotencyItem(requestId, outTxnId, fromAccountId, now);

        Map<String, AttributeValue> senderTxn = transactionItem(outTxnId, fromAccountId, TransactionType.TRANSFER_OUT,
                amount, signedOut, senderNewBalance, fromAccount.currency(), description, region, now,
                fromUserId, fromUsername, toAccountId, toUsername, requestId,
                PaymentMethodType.P2P, toUsername);

        Map<String, AttributeValue> receiverTxn = transactionItem(inTxnId, toAccountId, TransactionType.TRANSFER_IN,
                amount, amount, receiverNewBalance, toAccount.currency(), description, region, now,
                fromUserId, fromUsername, fromAccountId, fromUsername, requestId,
                PaymentMethodType.P2P, fromUsername);

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
                amount, signedOut, senderNewBalance, fromAccount.currency(), description, "POSTED", region, now,
                fromUserId, fromUsername, toAccountId, toUsername, PaymentMethodType.P2P, toUsername);
        TransactionResponse receiverResp = new TransactionResponse(inTxnId, toAccountId, TransactionType.TRANSFER_IN,
                amount, amount, receiverNewBalance, toAccount.currency(), description, "POSTED", region, now,
                fromUserId, fromUsername, fromAccountId, fromUsername, PaymentMethodType.P2P, fromUsername);
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
                        ":prefix", AttributeValue.fromS("TXN#")
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
        item.put("entityType", AttributeValue.fromS(ENTITY_USER));
        item.put("userId", AttributeValue.fromS(userId));
        item.put("username", AttributeValue.fromS(username));
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
        UserResponse user = new UserResponse(
                item.get("userId").s(),
                item.get("username").s(),
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
        item.put("entityType", AttributeValue.fromS(ENTITY_PAYMENT));
        item.put("type", AttributeValue.fromS(PaymentMethodType.DEMO_CARD));
        item.put("brand", AttributeValue.fromS(card.brand()));
        item.put("last4", AttributeValue.fromS(card.last4()));
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
        item.put("entityType", AttributeValue.fromS(ENTITY_PAYMENT));
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
        return new PaymentMethodResponse(
                item.get("type").s(),
                item.containsKey("brand") ? item.get("brand").s() : null,
                item.get("maskedReference").s(),
                item.containsKey("last4") ? item.get("last4").s() : null,
                Instant.parse(item.get("linkedAt").s())
        );
    }

    @Override
    public void logAuditEvent(String userId, String username, String eventType,
                              String ip, String userAgent, String region, String details) {
        String eventId = UlidCreator.getUlid().toString();
        Instant now = Instant.now();
        long ttl = now.getEpochSecond() + AUDIT_TTL_SECONDS;

        Map<String, AttributeValue> item = new HashMap<>();
        String pk = auditUserPk(resolveAuditUserId(userId, username));
        item.put("pk", AttributeValue.fromS(pk));
        item.put("sk", AttributeValue.fromS("EVENT#" + eventId));
        item.put("entityType", AttributeValue.fromS(ENTITY_AUDIT));
        item.put("eventId", AttributeValue.fromS(eventId));
        item.put("eventType", AttributeValue.fromS(eventType));
        item.put("region", AttributeValue.fromS(region == null ? "unknown" : region));
        item.put("createdAt", AttributeValue.fromS(now.toString()));
        item.put("ttl", AttributeValue.fromN(Long.toString(ttl)));
        if (ip != null && !ip.isBlank()) {
            item.put("ip", AttributeValue.fromS(ip));
        }
        if (userAgent != null && !userAgent.isBlank()) {
            item.put("userAgent", AttributeValue.fromS(userAgent.length() > 256 ? userAgent.substring(0, 256) : userAgent));
        }
        if (username != null && !username.isBlank()) {
            item.put("username", AttributeValue.fromS(username));
        }
        if (userId != null && !userId.isBlank()) {
            item.put("userId", AttributeValue.fromS(userId));
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
                        ":prefix", AttributeValue.fromS("EVENT#")
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
        return new AuditEventResponse(
                item.get("eventId").s(),
                item.get("eventType").s(),
                item.containsKey("region") ? item.get("region").s() : null,
                item.containsKey("ip") ? item.get("ip").s() : null,
                item.containsKey("userAgent") ? item.get("userAgent").s() : null,
                item.containsKey("details") ? item.get("details").s() : null,
                Instant.parse(item.get("createdAt").s())
        );
    }

    private Map<String, AttributeValue> idempotencyItem(String requestId, String transactionId, String accountId, Instant now) {
        long ttl = now.getEpochSecond() + IDEMPOTENCY_TTL_SECONDS;
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(idempotencyPk(requestId)));
        item.put("sk", AttributeValue.fromS(SK_METADATA));
        item.put("entityType", AttributeValue.fromS(ENTITY_IDEMPOTENCY));
        item.put("requestId", AttributeValue.fromS(requestId));
        item.put("transactionId", AttributeValue.fromS(transactionId));
        item.put("accountId", AttributeValue.fromS(accountId));
        item.put("createdAt", AttributeValue.fromS(now.toString()));
        item.put("ttl", AttributeValue.fromN(Long.toString(ttl)));
        return item;
    }

    private Map<String, AttributeValue> transactionItem(String transactionId, String accountId, String type,
                                                        BigDecimal amount, BigDecimal signedAmount, BigDecimal balanceAfter,
                                                        String currency, String description, String region, Instant now,
                                                        String userId, String username,
                                                        String counterpartyAccountId, String counterpartyUsername,
                                                        String requestId, String paymentMethod, String paymentReference) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(accountPk(accountId)));
        item.put("sk", AttributeValue.fromS(txnSk(transactionId)));
        item.put("gsi1pk", AttributeValue.fromS(transactionGsiPk(transactionId)));
        item.put("gsi1sk", AttributeValue.fromS(transactionGsiPk(transactionId)));
        item.put("entityType", AttributeValue.fromS(ENTITY_TRANSACTION));
        item.put("transactionId", AttributeValue.fromS(transactionId));
        item.put("accountId", AttributeValue.fromS(accountId));
        item.put("type", AttributeValue.fromS(type));
        item.put("amount", AttributeValue.fromN(amount.toPlainString()));
        item.put("signedAmount", AttributeValue.fromN(signedAmount.toPlainString()));
        item.put("balanceAfter", AttributeValue.fromN(balanceAfter.toPlainString()));
        item.put("currency", AttributeValue.fromS(currency));
        item.put("description", AttributeValue.fromS(description));
        item.put("status", AttributeValue.fromS("POSTED"));
        item.put("region", AttributeValue.fromS(region));
        item.put("createdAt", AttributeValue.fromS(now.toString()));
        item.put("requestId", AttributeValue.fromS(requestId));
        if (userId != null) {
            item.put("createdByUserId", AttributeValue.fromS(userId));
        }
        if (username != null) {
            item.put("createdByUsername", AttributeValue.fromS(username));
        }
        if (counterpartyAccountId != null) {
            item.put("counterpartyAccountId", AttributeValue.fromS(counterpartyAccountId));
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
        BigDecimal amount = new BigDecimal(item.get("amount").n());
        BigDecimal signed = item.containsKey("signedAmount") ? new BigDecimal(item.get("signedAmount").n()) : amount;
        BigDecimal balanceAfter = item.containsKey("balanceAfter") ? new BigDecimal(item.get("balanceAfter").n()) : null;
        String type = item.containsKey("type") ? item.get("type").s() : TransactionType.DEPOSIT;
        return new TransactionResponse(
                item.get("transactionId").s(),
                item.get("accountId").s(),
                type,
                amount,
                signed,
                balanceAfter,
                item.get("currency").s(),
                item.get("description").s(),
                item.get("status").s(),
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

    private static String accountPk(String accountId) {
        return "ACCOUNT#" + accountId;
    }

    private static String txnSk(String transactionId) {
        return "TXN#" + transactionId;
    }

    private static String transactionGsiPk(String transactionId) {
        return "TXN#" + transactionId;
    }

    private static String idempotencyPk(String requestId) {
        return "IDEMPOTENCY#" + requestId;
    }

    private static String userPk(String username) {
        return "USER#" + username;
    }

    private static String paymentUserPk(String userId) {
        return "PAYMENT#USER#" + userId;
    }

    private static String auditUserPk(String userId) {
        return "AUDIT#USER#" + userId;
    }

    private static String resolveAuditUserId(String userId, String username) {
        if (userId != null && !userId.isBlank()) {
            return userId;
        }
        return AUDIT_UNKNOWN_USER;
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
