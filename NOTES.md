# Notes

## Plan and key decisions

- Add database-backed idempotency records and associate successful redemption ledger entries with their request key.
- Lock the account row in the database before balance validation and deduction; do not use in-memory locks, since requests may reach different service instances.
- Use the globally unique `idempotency_record.idempotency_key` primary key to arbitrate duplicate requests. Persist the balance change, successful response, and `REDEEM` transaction in one database transaction.
- Keep the transaction key nullable for existing `EARN` entries, and uniquely index non-null keys so one key cannot produce multiple transaction entries.
- Store ledger `points` as signed balance deltas: positive for `EARN`, negative for `REDEEM`. The request still supplies a positive redemption amount.
- Use `VARCHAR(255)` for idempotency keys and store the SHA-256 request hash as 64 hexadecimal characters; store the original response as PostgreSQL `JSONB`.
- Use R2DBC `SELECT ... FOR UPDATE` for account row locking. The requested JPA `@Lock` annotation is not applicable because this service uses `DatabaseClient` and Spring Data R2DBC, not JPA.
- Map redemption domain exceptions to RFC 7807 statuses through the existing `ApiExceptionHandler`.
- Return `201 Created` for both first-time redemptions and idempotent replays; the replay contract permits either `200` or `201`.
- Bind `Idempotency-Key` as optional at the controller boundary and validate it in the service, ensuring missing-header errors use the domain ProblemDetail mapping.

## How I used AI

| Step | Tool | What I asked | Was it right? What did I change? |
|---|---|---|---|
| Phase 1 schema design | GitHub Copilot | Review the assignment, repository rules, and current schema; draft idempotency and redemption transaction storage. | Identified that `CREATE TABLE IF NOT EXISTS` does not upgrade an existing `points_transaction` table, so the schema uses an additive `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`. |
| Phase 2 persistence and exceptions | GitHub Copilot | Add persistence repositories, the account row-lock query, and domain exceptions/status mappings. | Replaced the requested JPA `@Lock` approach with equivalent `SELECT ... FOR UPDATE` SQL to match the app's existing R2DBC architecture. An initial JSON codec reference failed because the driver is runtime-scoped; switched to SQL `CAST` for JSONB binding. |
| Phase 3 redemption service | GitHub Copilot | Implement request hashing, idempotency replay/conflict behavior, locked deduction, and duplicate-key recovery. | Adapted the requested `Long` account ID to the current string-backed account key and used record accessors consistent with the existing domain model. Concurrency tests later identified and corrected transaction ordering and a post-lock idempotency recheck. |
| Phase 4 concurrency tests | GitHub Copilot | Add simultaneous redemption and same-key retry tests. | The database FK failure exposed that the idempotency row must be saved before its ledger row. The retry test also exposed a missing idempotency recheck after waiting for the account lock; both were corrected in the service. |
| Phase 5 endpoint and PR preparation | GitHub Copilot | Add the redemption endpoint, verify RFC 7807 status mapping, and document decisions, corrections, trade-offs, and next steps. | Kept the existing advice handler, made the header optional at binding so domain validation can produce ProblemDetail, and extracted the request record as a public type for JSON binding. |

## How I know it works

- `mvn test`: 3 existing PostgreSQL/Testcontainers earn integration tests passed; schema initialization succeeded on a fresh PostgreSQL 16 database.
- The existing-table upgrade path uses `ADD COLUMN IF NOT EXISTS` but is not directly covered by the current tests.
- Phase 2 `mvn test`: the same 3 tests passed, confirming Java compilation and Spring context startup. New repository SQL methods are not yet directly exercised; add focused repository/redemption tests in later phases.
- Phase 4 focused concurrency tests: 2 passed against PostgreSQL Testcontainers, proving 3/10 unique redemptions succeed from a 300-point balance and all 5 same-key retries replay one redemption from a 100-point balance.
- Full `mvn test` after Phase 4: all 5 tests passed (3 earn integration tests and 2 redemption concurrency tests).
- Phase 5 endpoint tests verify successful creation and replay, missing/invalid input, unknown account, insufficient balance, and idempotency conflict responses.
- Full `mvn test` after Phase 5: all 8 tests passed (3 earn, 2 service concurrency, and 3 HTTP integration tests) against PostgreSQL Testcontainers.

## Time trade-offs

- Return `201 Created` for an idempotent replay as well as a first-time redemption; both are allowed by the contract, and the service does not expose replay metadata to the controller.
- Keep the repository's existing startup-applied `schema.sql` and R2DBC `DatabaseClient` approach rather than introducing a migration framework or switching persistence stacks during the timed task.
- Use direct service invocation for concurrency tests to focus on database locking and idempotency; HTTP tests separately cover route binding and response mapping.

## What I'd do with more time

- Replace startup-applied `schema.sql` with versioned Flyway migrations, including an explicit existing-database upgrade test.
- Add HTTP-level concurrent duplicate and same-key/different-payload tests; current concurrency tests invoke the service directly, while controller tests verify endpoint status and body mapping.
- Consider exposing replay metadata from the service if the API later needs to distinguish `200` replays from `201` first-time creations.
- Review account ID typing and move all endpoint/service/repository layers to one explicit identifier type.

## PR summary

- Adds `POST /accounts/{accountId}/redemptions` with database-backed idempotency, locked balance deductions, and RFC 7807 errors.
- Uses PostgreSQL row locks and unique constraints for multi-instance concurrency; successful deductions and idempotency responses are committed with one transaction.
- Verified with `mvn test` (8 passing tests). Follow-ups: versioned schema migrations and an existing-schema upgrade test.
