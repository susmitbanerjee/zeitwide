-- Calendars and the official sources that describe them.

-- Lets the calendar_entry exclusion constraint compare plain columns with = in GiST.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE calendar (
    id               text        PRIMARY KEY
                                 CHECK (id ~ '^[A-Z0-9]+(-[A-Z0-9]+)*$' AND length(id) <= 64),
    name             text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    kind             text        NOT NULL CHECK (kind IN ('rail', 'public', 'exchange')),
    timezone         text        NOT NULL CHECK (length(timezone) BETWEEN 1 AND 64),
    -- Rules are data in the dataset release, not code (see conformance/).
    weekend_rule     jsonb       NOT NULL CHECK (jsonb_typeof(weekend_rule) = 'object'),
    current_revision bigint      NOT NULL DEFAULT 0 CHECK (current_revision >= 0),
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE source (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug        text        NOT NULL UNIQUE
                            CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(slug) <= 64),
    publisher   text        NOT NULL CHECK (length(publisher) BETWEEN 1 AND 200),
    url         text        NOT NULL CHECK (url ~ '^https://' AND length(url) <= 2048),
    parser_name text        NOT NULL CHECK (length(parser_name) BETWEEN 1 AND 100),
    schedule    text        NOT NULL CHECK (length(schedule) BETWEEN 1 AND 100),
    role        text        NOT NULL CHECK (role IN ('primary', 'corroborating')),
    enabled     boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now()
);
