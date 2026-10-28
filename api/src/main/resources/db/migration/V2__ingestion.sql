-- What the pipeline fetched, what it read from it, and the changes it proposes.

CREATE TABLE source_snapshot (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id    bigint      NOT NULL REFERENCES source (id),
    fetched_at   timestamptz NOT NULL,
    sha256       bytea       NOT NULL CHECK (octet_length(sha256) = 32),
    object_key   text        NOT NULL CHECK (length(object_key) BETWEEN 1 AND 1024),
    http_status  smallint    NOT NULL CHECK (http_status BETWEEN 100 AND 599),
    content_type text        CHECK (length(content_type) <= 255),
    byte_size    bigint      NOT NULL CHECK (byte_size >= 0),
    -- Storing only changed bytes keeps re-runs idempotent.
    UNIQUE (source_id, sha256)
);

-- Source health: latest snapshot per source.
CREATE INDEX source_snapshot_source_fetched_idx ON source_snapshot (source_id, fetched_at DESC);

CREATE TABLE observation (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    snapshot_id    bigint      NOT NULL REFERENCES source_snapshot (id),
    calendar_id    text        NOT NULL REFERENCES calendar (id),
    entry_date     date        NOT NULL,
    is_open        boolean     NOT NULL,
    reason         text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    parser_version text        NOT NULL CHECK (length(parser_version) BETWEEN 1 AND 50),
    observed_at    timestamptz NOT NULL DEFAULT now(),
    -- Re-parsing the same snapshot with the same parser adds nothing.
    UNIQUE (snapshot_id, calendar_id, entry_date, parser_version)
);

-- Corroboration: every source's view of one date.
CREATE INDEX observation_calendar_date_idx ON observation (calendar_id, entry_date);

CREATE TABLE candidate_change (
    id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    calendar_id      text        NOT NULL REFERENCES calendar (id),
    entry_date       date        NOT NULL,
    before_state     jsonb       CHECK (before_state IS NULL OR jsonb_typeof(before_state) = 'object'),
    after_state      jsonb       NOT NULL CHECK (jsonb_typeof(after_state) = 'object'),
    status           text        NOT NULL DEFAULT 'pending'
                                 CHECK (status IN ('pending', 'approved', 'rejected', 'published')),
    reviewer_subject text        CHECK (length(reviewer_subject) BETWEEN 1 AND 255),
    reviewed_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (status = 'pending' OR (reviewer_subject IS NOT NULL AND reviewed_at IS NOT NULL))
);

-- At most one open proposal per date; the review queue sorts by urgency (date).
CREATE UNIQUE INDEX candidate_change_pending_idx
    ON candidate_change (calendar_id, entry_date) WHERE status = 'pending';
CREATE INDEX candidate_change_queue_idx
    ON candidate_change (entry_date, id) WHERE status = 'pending';

CREATE TABLE candidate_observation (
    candidate_id   bigint NOT NULL REFERENCES candidate_change (id),
    observation_id bigint NOT NULL REFERENCES observation (id),
    PRIMARY KEY (candidate_id, observation_id)
);
