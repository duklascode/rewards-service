package com.example.rewards.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class RedemptionService {

    private static final TypeReference<Map<String, Object>> RESPONSE_TYPE = new TypeReference<>() {
    };

    private final AccountRepository accounts;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final PointsTransactionRepository transactions;
    private final ObjectMapper objectMapper;
    private final TransactionalOperator transactionOperator;

    public RedemptionService(
            AccountRepository accounts,
            IdempotencyRecordRepository idempotencyRecords,
            PointsTransactionRepository transactions,
            ObjectMapper objectMapper,
            ReactiveTransactionManager transactionManager) {
        this.accounts = accounts;
        this.idempotencyRecords = idempotencyRecords;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.transactionOperator = TransactionalOperator.create(transactionManager);
    }

    public Mono<Map<String, Object>> redeemPoints(
            String idempotencyKey,
            Long accountId,
            RedeemPointsRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Mono.error(new InvalidRequestException("Idempotency-Key header is required"));
        }
        if (accountId == null) {
            return Mono.error(new InvalidRequestException("Account ID is required"));
        }
        if (request == null || request.points() <= 0) {
            return Mono.error(new InvalidRequestException("Points must be greater than zero"));
        }
        if (request.rewardCode() == null || request.rewardCode().isBlank()) {
            return Mono.error(new InvalidRequestException("Reward code must not be blank"));
        }

        String requestHash = hashRequest(request);
        String accountKey = accountId.toString();

        return idempotencyRecords.findByKey(idempotencyKey)
                .flatMap(record -> replayOrConflict(record, requestHash))
                .switchIfEmpty(Mono.defer(() -> transactionOperator.transactional(
                                redeemNew(idempotencyKey, accountId, accountKey, request, requestHash))
                        .onErrorResume(DataIntegrityViolationException.class, error ->
                                idempotencyRecords.findByKey(idempotencyKey)
                                        .flatMap(record -> replayOrConflict(record, requestHash))
                                        .switchIfEmpty(Mono.error(error)))));
    }

    private Mono<Map<String, Object>> redeemNew(
            String idempotencyKey,
            Long accountId,
            String accountKey,
            RedeemPointsRequest request,
            String requestHash) {
        return accounts.findByIdForUpdate(accountKey)
                .switchIfEmpty(Mono.error(new AccountNotFoundException(accountKey)))
                .flatMap(account -> idempotencyRecords.findByKey(idempotencyKey)
                        .flatMap(record -> replayOrConflict(record, requestHash))
                        .switchIfEmpty(Mono.defer(() -> {
                            if (account.balance() < request.points()) {
                                return Mono.error(new InsufficientPointsException(
                                        accountKey, request.points(), account.balance()));
                            }

                            return accounts.addPoints(accountKey, -request.points())
                                    .flatMap(balanceAfter -> {
                                        Map<String, Object> response = Map.of(
                                                "redemptionId", UUID.randomUUID().toString(),
                                                "accountId", accountId,
                                                "points", request.points(),
                                                "rewardCode", request.rewardCode(),
                                                "balanceAfter", balanceAfter);

                                        return idempotencyRecords.save(
                                                        idempotencyKey,
                                                        accountKey,
                                                        requestHash,
                                                        serialize(response),
                                                        201)
                                                .then(transactions.recordRedemption(
                                                        accountKey, request.points(), idempotencyKey))
                                                .thenReturn(response);
                                    });
                        })));
    }

    private Mono<Map<String, Object>> replayOrConflict(IdempotencyRecord record, String requestHash) {
        if (!record.requestHash().equals(requestHash)) {
            return Mono.error(new IdempotencyConflictException(record.idempotencyKey()));
        }
        return Mono.fromCallable(() -> objectMapper.readValue(record.responseBody(), RESPONSE_TYPE));
    }

    private String serialize(Map<String, Object> response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize redemption response", exception);
        }
    }

    private static String hashRequest(RedeemPointsRequest request) {
        String payload = request.rewardCode().length() + ":" + request.rewardCode() + ":" + request.points();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}