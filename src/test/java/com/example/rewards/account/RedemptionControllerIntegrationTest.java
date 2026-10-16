package com.example.rewards.account;

import com.example.rewards.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RedemptionControllerIntegrationTest {

    @Autowired
    WebTestClient client;

    @Autowired
    AccountRepository accounts;

    @Test
    void createsRedemptionAndReplaysOriginalResult() {
        long accountId = accountWithBalance(300);
        String idempotencyKey = "http-replay-" + accountId;

        redeem(accountId, idempotencyKey, 100, "COFFEE-SMALL")
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.redemptionId").isNotEmpty()
                .jsonPath("$.accountId").isEqualTo(accountId)
                .jsonPath("$.points").isEqualTo(100)
                .jsonPath("$.rewardCode").isEqualTo("COFFEE-SMALL")
                .jsonPath("$.balanceAfter").isEqualTo(200);

        redeem(accountId, idempotencyKey, 100, "COFFEE-SMALL")
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.redemptionId").isNotEmpty()
                .jsonPath("$.balanceAfter").isEqualTo(200);

        client.get().uri("/accounts/{accountId}", accountId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.balance").isEqualTo(200);
    }

    @Test
    void mapsDomainFailuresToProblemDetails() {
        long accountId = accountWithBalance(100);
        String key = "http-errors-" + accountId;

        redeem(accountId, null, 10, "COFFEE-SMALL")
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.status").isEqualTo(400);

        redeem(accountId, key, 0, "COFFEE-SMALL")
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.status").isEqualTo(400);

        redeem(accountId, key, 10, " ")
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.status").isEqualTo(400);

        redeem(accountId + 1, "unknown-" + accountId, 10, "COFFEE-SMALL")
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.status").isEqualTo(404);

        redeem(accountId, "insufficient-" + accountId, 101, "COFFEE-SMALL")
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.status").isEqualTo(422);
    }

    @Test
    void reusedKeyWithDifferentPayloadReturnsConflictProblemDetail() {
        long accountId = accountWithBalance(300);
        String idempotencyKey = "http-conflict-" + accountId;

        redeem(accountId, idempotencyKey, 100, "COFFEE-SMALL")
                .expectStatus().isCreated();

        redeem(accountId, idempotencyKey, 200, "COFFEE-SMALL")
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.status").isEqualTo(409);
    }

    private WebTestClient.ResponseSpec redeem(long accountId, String idempotencyKey, long points, String rewardCode) {
        WebTestClient.RequestBodySpec request = client.post()
                .uri("/accounts/{accountId}/redemptions", accountId)
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.bodyValue(Map.of("points", points, "rewardCode", rewardCode)).exchange();
    }

    private long accountWithBalance(long balance) {
        long accountId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        accounts.addPoints(Long.toString(accountId), balance).block();
        return accountId;
    }
}
