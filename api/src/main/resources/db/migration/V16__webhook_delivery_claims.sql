ALTER TABLE webhook_subscription
    ADD COLUMN idempotency_key_hash bytea
        CHECK (idempotency_key_hash IS NULL OR octet_length(idempotency_key_hash) = 32);

CREATE UNIQUE INDEX webhook_subscription_account_idempotency_idx
    ON webhook_subscription (account_id, idempotency_key_hash)
    WHERE idempotency_key_hash IS NOT NULL;

ALTER TABLE webhook_delivery
    ADD COLUMN locked_until timestamptz,
    ADD COLUMN claim_token uuid;

ALTER TABLE webhook_delivery
    DROP CONSTRAINT webhook_delivery_status_check,
    ADD CONSTRAINT webhook_delivery_status_check
        CHECK (status IN ('pending', 'sending', 'succeeded', 'failed', 'abandoned'));

CREATE INDEX webhook_delivery_sending_idx
    ON webhook_delivery (locked_until, id) WHERE status = 'sending';
