package com.example.rewards.account;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class IdempotencyRecordRepository {

    private final DatabaseClient db;

    public IdempotencyRecordRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<IdempotencyRecord> findByKey(String idempotencyKey) {
        return db.sql("""
                           SELECT idempotency_key, account_id, request_hash,
                               CAST(response_body AS TEXT) AS response_body, status_code, created_at
                        FROM idempotency_record
                        WHERE idempotency_key = :idempotencyKey
                        """)
                .bind("idempotencyKey", idempotencyKey)
                .map((row, metadata) -> new IdempotencyRecord(
                        row.get("idempotency_key", String.class),
                        row.get("account_id", String.class),
                        row.get("request_hash", String.class),
                        row.get("response_body", String.class),
                        row.get("status_code", Short.class).intValue(),
                        row.get("created_at", java.time.OffsetDateTime.class)))
                .one();
    }

    public Mono<IdempotencyRecord> save(
            String idempotencyKey,
            String accountId,
            String requestHash,
            String responseBody,
            int statusCode) {
        return db.sql("""
                        INSERT INTO idempotency_record
                            (idempotency_key, account_id, request_hash, response_body, status_code)
                        VALUES (:idempotencyKey, :accountId, :requestHash, CAST(:responseBody AS JSONB), :statusCode)
                        RETURNING idempotency_key, account_id, request_hash,
                                  CAST(response_body AS TEXT) AS response_body, status_code, created_at
                        """)
                .bind("idempotencyKey", idempotencyKey)
                .bind("accountId", accountId)
                .bind("requestHash", requestHash)
                .bind("responseBody", responseBody)
                .bind("statusCode", (short) statusCode)
                .map((row, metadata) -> new IdempotencyRecord(
                        row.get("idempotency_key", String.class),
                        row.get("account_id", String.class),
                        row.get("request_hash", String.class),
                        row.get("response_body", String.class),
                        row.get("status_code", Short.class).intValue(),
                        row.get("created_at", java.time.OffsetDateTime.class)))
                .one();
    }
}