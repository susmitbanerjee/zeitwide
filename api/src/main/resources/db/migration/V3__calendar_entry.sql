-- Bitemporal calendar entries: entry_date is the day described (valid time) and
-- recorded_during is when SettleCal believed it (transaction time). A change closes
-- the current version and inserts a new one; rows are never edited or deleted.

CREATE TABLE calendar_entry (
    id              bigint    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    calendar_id     text      NOT NULL REFERENCES calendar (id),
    entry_date      date      NOT NULL,
    is_open         boolean   NOT NULL,
    reason          text      NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    basis           text      NOT NULL CHECK (basis IN ('published', 'projected')),
    revision        bigint    NOT NULL CHECK (revision > 0),
    recorded_during tstzrange NOT NULL,
    CONSTRAINT calendar_entry_recorded_during_check CHECK (
        NOT isempty(recorded_during)
        AND NOT lower_inf(recorded_during)
        AND lower_inc(recorded_during)
        AND NOT upper_inc(recorded_during)
    ),
    -- Deferrable (but checked at the end of each statement) so publish_entry_version
    -- can close the old version and insert the new one in a single statement.
    -- Its GiST index also serves as-of lookups: calendar_id, entry_date, @> instant.
    CONSTRAINT calendar_entry_no_overlap EXCLUDE USING gist (
        calendar_id WITH =,
        entry_date WITH =,
        recorded_during WITH &&
    ) DEFERRABLE INITIALLY IMMEDIATE
);

-- Closures in a date range, as currently known.
CREATE INDEX calendar_entry_current_closures_idx
    ON calendar_entry (calendar_id, entry_date)
    WHERE NOT is_open AND upper_inf(recorded_during);

CREATE FUNCTION calendar_entry_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'calendar_entry is append-only: rows are never deleted'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- The one allowed update: set the end of a still-open version, nothing else.
    IF (NEW.id, NEW.calendar_id, NEW.entry_date, NEW.is_open, NEW.reason, NEW.basis, NEW.revision)
           IS DISTINCT FROM
           (OLD.id, OLD.calendar_id, OLD.entry_date, OLD.is_open, OLD.reason, OLD.basis, OLD.revision)
       OR NOT upper_inf(OLD.recorded_during)
       OR upper_inf(NEW.recorded_during)
       OR lower(NEW.recorded_during) <> lower(OLD.recorded_during) THEN
        RAISE EXCEPTION 'calendar_entry is append-only: publish a new version with publish_entry_version'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER calendar_entry_append_only
    BEFORE UPDATE OR DELETE ON calendar_entry
    FOR EACH ROW EXECUTE FUNCTION calendar_entry_guard();

-- Closes the current version of (calendar, date), if any, at p_recorded_at and
-- inserts the new version from p_recorded_at onwards, in one statement: either both
-- happen or neither does. A concurrent publish of the same date fails on the
-- exclusion constraint instead of creating overlapping versions.
CREATE FUNCTION publish_entry_version(
    p_calendar_id text,
    p_entry_date  date,
    p_is_open     boolean,
    p_reason      text,
    p_basis       text,
    p_revision    bigint,
    p_recorded_at timestamptz DEFAULT now()
) RETURNS calendar_entry
LANGUAGE sql
AS $$
    WITH closed AS (
        UPDATE calendar_entry
        SET recorded_during = tstzrange(lower(recorded_during), p_recorded_at, '[)')
        WHERE calendar_id = p_calendar_id
          AND entry_date = p_entry_date
          AND upper_inf(recorded_during)
        RETURNING id
    )
    INSERT INTO calendar_entry (
        calendar_id, entry_date, is_open, reason, basis, revision, recorded_during
    )
    SELECT p_calendar_id, p_entry_date, p_is_open, p_reason, p_basis, p_revision,
           tstzrange(p_recorded_at, NULL, '[)')
    -- Reading the CTE makes the close run before the insert.
    FROM (SELECT count(*) FROM closed) AS close_first
    RETURNING *;
$$;
