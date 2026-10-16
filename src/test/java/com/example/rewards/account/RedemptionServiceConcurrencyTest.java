package com.example.rewards.account;

import com.example.rewards.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedemptionServiceConcurrencyTest {

    @Autowired
    RedemptionService redemptions;

    @Autowired
    AccountRepository accounts;

    @Autowired
    IdempotencyRecordRepository idempotencyRecords;

    @Autowired
    PointsTransactionRepository transactions;

    @Test
    void concurrentRedemptionsNeverMakeBalanceNegative() throws Exception {
        long accountId = newAccountId();
        accounts.addPoints(Long.toString(accountId), 300).block();

        int requestCount = 10;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        List<Future<AttemptResult>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < requestCount; index++) {
                String idempotencyKey = "balance-race-" + accountId + "-" + index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    awaitStart(start);
                    return attempt(idempotencyKey, accountId, 100, "COFFEE-SMALL");
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers did not become ready");
            start.countDown();

            List<AttemptResult> results = new ArrayList<>();
            for (Future<AttemptResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }

            List<AttemptResult> successful = results.stream()
                    .filter(result -> result.statusCode() == 201)
                    .toList();
            List<AttemptResult> insufficient = results.stream()
                    .filter(result -> result.statusCode() == 422)
                    .toList();

            assertEquals(3, successful.size());
            assertEquals(7, insufficient.size());
            assertEquals(0, accounts.findById(Long.toString(accountId)).block().balance());

            for (int index = 0; index < requestCount; index++) {
                String idempotencyKey = "balance-race-" + accountId + "-" + index;
                if (results.get(index).statusCode() == 201) {
                    IdempotencyRecord record = idempotencyRecords.findByKey(idempotencyKey).block();
                    assertNotNull(record);
                    assertEquals(201, record.statusCode());
                    assertNotNull(transactions.findByIdempotencyKey(idempotencyKey).block());
                } else {
                    assertEquals(422, results.get(index).statusCode());
                    assertNull(idempotencyRecords.findByKey(idempotencyKey).block());
                }
            }
        } finally {
            start.countDown();
            shutdown(executor);
        }
    }

    @Test
    void concurrentRetriesWithSameKeyReplayTheOriginalRedemption() throws Exception {
        long accountId = newAccountId();
        accounts.addPoints(Long.toString(accountId), 100).block();

        int requestCount = 5;
        String idempotencyKey = "retry-race-" + accountId;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < requestCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    awaitStart(start);
                    return redemptions.redeemPoints(
                                    idempotencyKey,
                                    accountId,
                                    new RedeemPointsRequest(100, "COFFEE-SMALL"))
                            .block(Duration.ofSeconds(15));
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers did not become ready");
            start.countDown();

            List<Map<String, Object>> results = new ArrayList<>();
            for (Future<Map<String, Object>> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }

            Map<String, Object> original = results.get(0);
            for (Map<String, Object> result : results) {
                assertEquals(normalize(original), normalize(result));
            }

            assertEquals(0, accounts.findById(Long.toString(accountId)).block().balance());
            IdempotencyRecord record = idempotencyRecords.findByKey(idempotencyKey).block();
            assertNotNull(record);
            assertEquals(201, record.statusCode());
            assertNotNull(transactions.findByIdempotencyKey(idempotencyKey).block());
        } finally {
            start.countDown();
            shutdown(executor);
        }
    }

    private AttemptResult attempt(String idempotencyKey, long accountId, long points, String rewardCode) {
        try {
            Map<String, Object> response = redemptions.redeemPoints(
                            idempotencyKey,
                            accountId,
                            new RedeemPointsRequest(points, rewardCode))
                    .block(Duration.ofSeconds(15));
            return new AttemptResult(201, response);
        } catch (InsufficientPointsException exception) {
            return new AttemptResult(422, null);
        }
    }

    private static Map<String, Object> normalize(Map<String, Object> response) {
        return Map.of(
                "redemptionId", response.get("redemptionId").toString(),
                "accountId", response.get("accountId").toString(),
                "points", ((Number) response.get("points")).longValue(),
                "rewardCode", response.get("rewardCode"),
                "balanceAfter", ((Number) response.get("balanceAfter")).longValue());
    }

    private static long newAccountId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrent start", exception);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor did not terminate");
        }
    }

    private record AttemptResult(int statusCode, Map<String, Object> response) {
    }
}
