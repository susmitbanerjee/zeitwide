-- Metered usage (nobody is billed in the MVP) and Bucket4j rate-limit state.

-- One row per metered request; kept 30 days, then rolled up and deleted.
CREATE TABLE usage_event (
    id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  uuid         NOT NULL REFERENCES account (id),
    api_key_id  uuid         REFERENCES api_key (id),
    feature     feature_code NOT NULL,
    occurred_at timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX usage_event_occurred_idx ON usage_event (occurred_at);

-- Derived from usage_event by roll_up_usage; kept forever (it is small).
CREATE TABLE usage_daily (
    account_id    uuid         NOT NULL REFERENCES account (id),
    usage_date    date         NOT NULL,
    feature       feature_code NOT NULL,
    request_count bigint       NOT NULL CHECK (request_count >= 0),
    PRIMARY KEY (account_id, usage_date, feature)
);

-- Layout expected by bucket4j-postgresql: the API maps each limit key (API key,
-- account, anonymous client) to a 64-bit ID.
CREATE TABLE rate_limit_bucket (
    id         bigint PRIMARY KEY,
    state      bytea  NOT NULL,
    -- Epoch milliseconds after which the bucket is full again and can be dropped.
    expires_at bigint
);

CREATE INDEX rate_limit_bucket_expires_idx ON rate_limit_bucket (expires_at);
