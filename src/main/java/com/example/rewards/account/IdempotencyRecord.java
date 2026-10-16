package com.example.rewards.account;

import java.time.OffsetDateTime;

public record IdempotencyRecord(
        String idempotencyKey,
        String accountId,
        String requestHash,
        String responseBody,
        int statusCode,
        OffsetDateTime createdAt) {
}