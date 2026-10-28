-- Webhook subscriptions and the delivery log.

CREATE TABLE webhook_subscription (
    id                        uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id                uuid          NOT NULL REFERENCES account (id),
    url                       text          NOT NULL CHECK (url ~ '^https://' AND length(url) <= 2048),
    -- The HMAC secret must be recoverable to sign deliveries, so the API stores it
    -- encrypted (AES-GCM, key from Secret Manager), never in plain text.
    signing_secret_ciphertext bytea         NOT NULL
                                            CHECK (octet_length(signing_secret_ciphertext) BETWEEN 1 AND 1024),
    event_types               change_type[] NOT NULL CHECK (cardinality(event_types) BETWEEN 1 AND 16),
    -- Null means every calendar.
    calendar_ids              text[]        CHECK (cardinality(calendar_ids) BETWEEN 1 AND 100),
    created_at                timestamptz   NOT NULL DEFAULT now(),
    disabled_at               timestamptz
);

CREATE INDEX webhook_subscription_account_idx ON webhook_subscription (account_id);

CREATE TABLE webhook_delivery (
    id                   bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscription_id      uuid        NOT NULL REFERENCES webhook_subscription (id),
    outbox_id            bigint      NOT NULL REFERENCES outbox (id),
    status               text        NOT NULL DEFAULT 'pending'
                                     CHECK (status IN ('pending', 'succeeded', 'failed', 'abandoned')),
    attempts             integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at      timestamptz NOT NULL DEFAULT now(),
    last_response_status smallint    CHECK (last_response_status BETWEEN 100 AND 599),
    last_error           text        CHECK (length(last_error) <= 1000),
    created_at           timestamptz NOT NULL DEFAULT now(),
    delivered_at         timestamptz,
    UNIQUE (subscription_id, outbox_id)
);

-- Retries due now; the delivery log per subscription; daily clean-up.
CREATE INDEX webhook_delivery_pending_idx
    ON webhook_delivery (next_attempt_at, id) WHERE status = 'pending';
CREATE INDEX webhook_delivery_subscription_created_idx
    ON webhook_delivery (subscription_id, created_at DESC);
CREATE INDEX webhook_delivery_created_idx ON webhook_delivery (created_at);
