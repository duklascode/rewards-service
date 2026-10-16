package com.example.rewards.account;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PointsTransaction(
        UUID id,
        String accountId,
        long points,
        TransactionType type,
        String idempotencyKey,
        OffsetDateTime createdAt) {
}