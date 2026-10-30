ALTER TABLE api_key
    ADD COLUMN idempotency_key_hash bytea
    CHECK (idempotency_key_hash IS NULL OR octet_length(idempotency_key_hash) = 32);

CREATE UNIQUE INDEX api_key_account_idempotency_idx
    ON api_key (account_id, idempotency_key_hash)
    WHERE idempotency_key_hash IS NOT NULL;
