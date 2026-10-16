-- Applied on startup (spring.sql.init.mode=always). Safe to re-run.

CREATE TABLE IF NOT EXISTS points_account (
    id          VARCHAR(64)  PRIMARY KEY,
    balance     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Stores the original successful response for each globally unique request key.
CREATE TABLE IF NOT EXISTS idempotency_record (
    idempotency_key  VARCHAR(255) PRIMARY KEY,
    account_id       VARCHAR(64)  NOT NULL REFERENCES points_account (id),
    request_hash     VARCHAR(64)  NOT NULL,
    response_body    JSONB        NOT NULL,
    status_code      SMALLINT     NOT NULL CHECK (status_code IN (200, 201)),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Append-only history of every balance change.
CREATE TABLE IF NOT EXISTS points_transaction (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       VARCHAR(64)  NOT NULL REFERENCES points_account (id),
    type             VARCHAR(16)  NOT NULL,          -- EARN, REDEEM
    points           BIGINT       NOT NULL,
    idempotency_key  VARCHAR(255) REFERENCES idempotency_record (idempotency_key),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Upgrade databases where points_transaction already exists from an earlier startup.
ALTER TABLE points_transaction
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255)
        REFERENCES idempotency_record (idempotency_key);

CREATE INDEX IF NOT EXISTS idx_points_transaction_account ON points_transaction (account_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_points_transaction_idempotency_key
    ON points_transaction (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
