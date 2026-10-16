package com.example.rewards.account;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public class PointsTransactionRepository {

    private final DatabaseClient db;

    public PointsTransactionRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<PointsTransaction> findByIdempotencyKey(String idempotencyKey) {
        return db.sql("""
                        SELECT id, account_id, points, type, idempotency_key, created_at
                        FROM points_transaction
                        WHERE idempotency_key = :idempotencyKey
                        """)
                .bind("idempotencyKey", idempotencyKey)
                .map((row, metadata) -> new PointsTransaction(
                        row.get("id", UUID.class),
                        row.get("account_id", String.class),
                        row.get("points", Long.class),
                        TransactionType.valueOf(row.get("type", String.class)),
                        row.get("idempotency_key", String.class),
                        row.get("created_at", OffsetDateTime.class)))
                .one();
    }

    public Mono<Void> recordRedemption(String accountId, long points, String idempotencyKey) {
        return db.sql("""
                        INSERT INTO points_transaction (account_id, type, points, idempotency_key)
                        VALUES (:accountId, 'REDEEM', :points, :idempotencyKey)
                        """)
                .bind("accountId", accountId)
                .bind("points", Math.negateExact(points))
                .bind("idempotencyKey", idempotencyKey)
                .then();
    }
}