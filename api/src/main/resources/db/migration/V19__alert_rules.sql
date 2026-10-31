-- Alert rules: which published changes matter to an account. While an account has any
-- rule, its webhooks receive only changes that match one (see the outbox fan-out).
CREATE TABLE alert_rule (
    id                   uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id           uuid          NOT NULL REFERENCES account (id),
    name                 text          NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    calendar_ids         text[]        NOT NULL CHECK (cardinality(calendar_ids) BETWEEN 1 AND 20),
    event_types          change_type[] NOT NULL CHECK (cardinality(event_types) BETWEEN 1 AND 4),
    -- Null means any notice; 14 means "only short-notice changes".
    max_notice_days      integer       CHECK (max_notice_days BETWEEN 0 AND 3650),
    idempotency_key_hash bytea         CHECK (idempotency_key_hash IS NULL
                                              OR octet_length(idempotency_key_hash) = 32),
    created_at           timestamptz   NOT NULL DEFAULT now()
);

-- The account's rules, newest first; the fan-out looks them up by account too.
CREATE INDEX alert_rule_account_idx ON alert_rule (account_id, created_at DESC);
CREATE UNIQUE INDEX alert_rule_account_idempotency_idx
    ON alert_rule (account_id, idempotency_key_hash)
    WHERE idempotency_key_hash IS NOT NULL;
