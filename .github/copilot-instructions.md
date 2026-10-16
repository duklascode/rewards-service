# Repository Guidelines & AI Pair Programming Rules

## 1. Primary Directives & Constraints
- **Time Constraint:** This is a timed 60-minute technical assessment. Prioritize correctness and proof over complete feature breadth.
- **Workflow Protocol:** Work incrementally. Write failing tests before implementation. Execute tasks in isolated phases.
- **Documentation Duty:** Update `NOTES.md` continuously after every major milestone (trade-offs, concurrency design, AI mistakes caught).
- **Commit Discipline:** Suggest short, meaningful commit boundaries at logical steps (e.g., `feat: schema & idempotency storage`, `test: concurrent redemptions`, `feat: redemption service & atomic locking`).

---

## 2. Technical Stack & Architecture
- **Language & Framework:** Java 17+, Spring Boot, Spring Data JPA / JDBC, Flyway / Liquibase / `schema.sql`.
- **Database:** PostgreSQL / H2 (Modeled for multi-node deployments behind a load balancer).
- **Concurrency Control:**
  - **In-memory locks (e.g., `ReentrantLock`, `synchronized`) ARE STRICTLY FORBIDDEN.** The service runs in a multi-node environment.
  - Balance updates and points deductions **MUST** use database-level locking (`SELECT ... FOR UPDATE` or pessimistic write locks) or atomic SQL updates (`UPDATE account SET balance = balance - :points WHERE id = :id AND balance >= :points`).
- **Error Handling & Specs:**
  - Standardized Problem Details for HTTP errors (`RFC 7807` / `ProblemDetail`).
  - Strict HTTP status code adherence:
    - `201 Created` / `200 OK`: Successful redemption or matching idempotent replay.
    - `400 Bad Request`: Missing header, `points <= 0`, or blank `rewardCode`.
    - `404 Not Found`: Non-existent account.
    - `409 Conflict`: Idempotency key reused with a *different* payload, or concurrent key collision.
    - `422 Unprocessable Entity`: Insufficient account balance.

---

## 3. Idempotency & Concurrency Strategy
- **Idempotency Table Schema (`idempotency_key`):**
  - Store `key` (PRIMARY KEY), `account_id`, `request_hash` (SHA-256 of payload), `response_body` (JSON), `status_code`, and `created_at`.
  - Handle race conditions via database `UNIQUE` constraints on `key`. Duplicate insertion attempts must catch `DataIntegrityViolationException` / `DuplicateKeyException` and handle according to rules:
    - Identical payload -> Return saved original response (`200` or `201`).
    - Different payload -> Return `409 Conflict`.
- **Race Condition Safety:**
  - Always lock the `account` row prior to balance validation to prevent dirty/phantom reads under high contention.
  - Record every successful deduction in `points_transaction` with type `REDEEM`.

---

## 4. How You (Copilot) Should Interact With Me
1. **Plan First:** Before outputting multi-file code blocks, summarize your step-by-step approach and wait for confirmation.
2. **Phase Execution:** Produce changes in strictly isolated steps:
   - **Phase 1:** `schema.sql` / Liquibase changelogs & Entities.
   - **Phase 2:** Repository layer (locking queries) & DTOs/Exceptions.
   - **Phase 3:** `RedemptionService` idempotency & transaction isolation logic.
   - **Phase 4:** Controller & RFC 7807 Exception Handlers.
   - **Phase 5:** Integration tests (`ExecutorService` multi-threaded tests for Rules 1 & 2).
3. **No Assumptions:** If a domain detail is absent from the spec, ask or suggest a sane default and explicitly remind me to log it in `NOTES.md`.