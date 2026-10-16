package com.example.rewards.account;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class AccountRepository {

    private final DatabaseClient db;

    public AccountRepository(DatabaseClient db) {
        this.db = db;
    }

    /** Creates the account if needed and adds points. Returns the new balance. */
    public Mono<Long> addPoints(String accountId, long points) {
        return db.sql("""
                        INSERT INTO points_account (id, balance) VALUES (:id, :points)
                        ON CONFLICT (id) DO UPDATE
                           SET balance = points_account.balance + EXCLUDED.balance,
                               updated_at = now()
                        RETURNING balance
                        """)
                .bind("id", accountId)
                .bind("points", points)
                .map(row -> row.get("balance", Long.class))
                .one();
    }

    public Mono<Account> findById(String accountId) {
        return db.sql("SELECT id, balance FROM points_account WHERE id = :id")
                .bind("id", accountId)
                .map(row -> new Account(row.get("id", String.class), row.get("balance", Long.class)))
                .one();
    }

    public Mono<Account> findByIdForUpdate(String accountId) {
        return db.sql("SELECT id, balance FROM points_account WHERE id = :id FOR UPDATE")
                .bind("id", accountId)
                .map(row -> new Account(row.get("id", String.class), row.get("balance", Long.class)))
                .one();
    }

    public Mono<Void> recordTransaction(String accountId, TransactionType type, long points) {
        return db.sql("INSERT INTO points_transaction (account_id, type, points) VALUES (:accountId, :type, :points)")
                .bind("accountId", accountId)
                .bind("type", type.name())
                .bind("points", points)
                .then();
    }
}
