-- Accounts, members, API keys and plan entitlements. Every account is on 'free' in
-- the MVP; the other plans exist so turning them on is a data change.

CREATE DOMAIN plan_code AS text CHECK (VALUE IN ('free', 'pro', 'enterprise'));

CREATE DOMAIN feature_code AS text
    CHECK (VALUE ~ '^[a-z][a-z0-9_]*$' AND length(VALUE) <= 64);

CREATE TABLE account (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    plan       plan_code   NOT NULL DEFAULT 'free',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       uuid        NOT NULL REFERENCES account (id),
    -- Keycloak holds names and email addresses; only its stable subject ID is kept here.
    keycloak_subject text        NOT NULL UNIQUE CHECK (length(keycloak_subject) BETWEEN 1 AND 255),
    role             text        NOT NULL DEFAULT 'member' CHECK (role IN ('owner', 'member')),
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX app_user_account_idx ON app_user (account_id);

CREATE TABLE api_key (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    uuid        NOT NULL REFERENCES account (id),
    name          text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    -- Shown in the UI and used to find the row. The full key is shown once at
    -- creation and never stored; only the SHA-256 of its secret part is.
    prefix        text        NOT NULL UNIQUE CHECK (prefix ~ '^sc_(live|test)_[A-Za-z0-9]{8}$'),
    secret_sha256 bytea       NOT NULL CHECK (octet_length(secret_sha256) = 32),
    scopes        text[]      NOT NULL DEFAULT '{}' CHECK (cardinality(scopes) <= 16),
    created_by    uuid        REFERENCES app_user (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_used_at  timestamptz,
    revoked_at    timestamptz
);

CREATE INDEX api_key_account_idx ON api_key (account_id);

CREATE TABLE entitlement (
    plan        plan_code    NOT NULL,
    feature     feature_code NOT NULL,
    -- Null means unlimited.
    limit_value bigint       CHECK (limit_value >= 0),
    PRIMARY KEY (plan, feature)
);
