-- Evidence for each entry version, the change feed, the webhook outbox and releases.

CREATE TABLE entry_source (
    entry_id    bigint NOT NULL REFERENCES calendar_entry (id),
    snapshot_id bigint NOT NULL REFERENCES source_snapshot (id),
    PRIMARY KEY (entry_id, snapshot_id)
);

-- Shared by change_event.change_type and webhook_subscription.event_types.
CREATE DOMAIN change_type AS text
    CHECK (VALUE IN ('closure_added', 'closure_removed', 'closure_changed', 'basis_changed'));

CREATE TABLE change_event (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    change_type  change_type NOT NULL,
    calendar_id  text        NOT NULL REFERENCES calendar (id),
    entry_date   date        NOT NULL,
    entry_id     bigint      NOT NULL REFERENCES calendar_entry (id),
    before_state jsonb       CHECK (before_state IS NULL OR jsonb_typeof(before_state) = 'object'),
    after_state  jsonb       NOT NULL CHECK (jsonb_typeof(after_state) = 'object'),
    -- Negative when a change lands after the date it describes.
    notice_days  integer     NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now()
);

-- Changes since a time, across all calendars or for one; (published_at, id) is the
-- keyset cursor.
CREATE INDEX change_event_published_idx ON change_event (published_at, id);
CREATE INDEX change_event_calendar_published_idx ON change_event (calendar_id, published_at, id);

CREATE TABLE outbox (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    change_event_id bigint      NOT NULL REFERENCES change_event (id),
    event_type      change_type NOT NULL,
    payload         jsonb       NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    status          text        NOT NULL DEFAULT 'pending'
                                CHECK (status IN ('pending', 'dispatched', 'failed')),
    attempts        integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    dispatched_at   timestamptz,
    CHECK ((status = 'dispatched') = (dispatched_at IS NOT NULL))
);

-- The drain claims due rows in this order with FOR UPDATE SKIP LOCKED.
CREATE INDEX outbox_pending_idx ON outbox (next_attempt_at, id) WHERE status = 'pending';

CREATE TABLE dataset_release (
    id                 text        PRIMARY KEY CHECK (id ~ '^[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[0-9]+$'),
    -- The recorded-time instant the release captures, so it can be replayed exactly.
    as_of              timestamptz NOT NULL,
    calendar_revisions jsonb       NOT NULL CHECK (jsonb_typeof(calendar_revisions) = 'object'),
    export_sha256      bytea       NOT NULL CHECK (octet_length(export_sha256) = 32),
    created_at         timestamptz NOT NULL DEFAULT now()
);

-- Every response names the latest release.
CREATE INDEX dataset_release_created_idx ON dataset_release (created_at DESC);
