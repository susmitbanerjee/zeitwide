-- Pipeline bookkeeping: per-source check state, corroboration on candidates, and the
-- pipeline's access to these and to the Procrastinate job queue (V11).

CREATE TABLE source_state (
    source_id            bigint      PRIMARY KEY REFERENCES source (id),
    -- Validators for conditional requests, saved only after a fully processed fetch.
    etag                 text        CHECK (length(etag) <= 1024),
    last_modified        text        CHECK (length(last_modified) <= 100),
    -- Hash of the last snapshot that parsed and diffed cleanly. A snapshot that failed
    -- to parse doesn't count, so the same bytes are parsed again next time.
    last_parsed_sha256   bytea       CHECK (octet_length(last_parsed_sha256) = 32),
    last_checked_at      timestamptz,
    last_success_at      timestamptz,
    consecutive_failures integer     NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    last_error           text        CHECK (length(last_error) <= 1000),
    -- Set after repeated failures; the pipeline skips the source until someone resumes it.
    suspended_at         timestamptz
);

-- 'corroborated' means a second, independent source agrees, so the change is ready
-- for review; 'single_source' changes are flagged in the review queue.
ALTER TABLE candidate_change
    ADD COLUMN corroboration text NOT NULL DEFAULT 'single_source'
        CHECK (corroboration IN ('single_source', 'corroborated')),
    ADD COLUMN source_count integer NOT NULL DEFAULT 0 CHECK (source_count >= 0);

-- Replaces V9's version. Adds: sources the pipeline registers from its code, its
-- per-source state, corroboration columns, and the Procrastinate tables, sequences
-- and functions (matched by name so it also works before V11 exists).
CREATE OR REPLACE FUNCTION apply_role_grants() RETURNS SETOF text
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
DECLARE
    -- Login names match deploy/gcp/locals.tf.
    c_pipeline CONSTANT text := 'settlecal_pipeline';
    c_backup   CONSTANT text := 'settlecal_backup';
    v_object   record;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = c_pipeline) THEN
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), c_pipeline);
        EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', c_pipeline);
        EXECUTE format('GRANT SELECT ON calendar, source, calendar_entry TO %I', c_pipeline);
        EXECUTE format('GRANT UPDATE (current_revision) ON calendar TO %I', c_pipeline);
        -- Sources are modules in the pipeline's code; it keeps their rows in step.
        -- Admins still decide whether a source is enabled.
        EXECUTE format(
            'GRANT INSERT, UPDATE (publisher, url, parser_name, schedule, role) ON source TO %I',
            c_pipeline);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON source_state TO %I', c_pipeline);
        EXECUTE format(
            'GRANT SELECT, INSERT ON source_snapshot, observation, candidate_change,
                 candidate_observation, entry_source, change_event, outbox, dataset_release
             TO %I', c_pipeline);
        EXECUTE format(
            'GRANT UPDATE (status, reviewer_subject, reviewed_at, corroboration, source_count)
             ON candidate_change TO %I', c_pipeline);
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION
                 publish_entry_version(text, date, boolean, text, text, bigint, timestamptz),
                 roll_up_usage(timestamptz),
                 cleanup_old_rows(interval)
             TO %I', c_pipeline);

        FOR v_object IN
            SELECT c.relname, c.relkind FROM pg_class AS c
            WHERE c.relnamespace = 'public'::regnamespace
              AND c.relname LIKE 'procrastinate\_%'
              AND c.relkind IN ('r', 'S')
        LOOP
            IF v_object.relkind = 'r' THEN
                EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO %I',
                               v_object.relname, c_pipeline);
            ELSE
                EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %I TO %I',
                               v_object.relname, c_pipeline);
            END IF;
        END LOOP;

        FOR v_object IN
            SELECT p.oid::regprocedure AS signature FROM pg_proc AS p
            WHERE p.pronamespace = 'public'::regnamespace
              AND p.proname LIKE 'procrastinate\_%'
        LOOP
            EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO %I', v_object.signature, c_pipeline);
        END LOOP;

        RETURN NEXT c_pipeline;
    ELSE
        RAISE NOTICE 'Role % does not exist; skipping its grants', c_pipeline;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = c_backup) THEN
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), c_backup);
        EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', c_backup);
        EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA public TO %I', c_backup);
        EXECUTE format('GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', c_backup);
        RETURN NEXT c_backup;
    ELSE
        RAISE NOTICE 'Role % does not exist; skipping its grants', c_backup;
    END IF;
END;
$$;
