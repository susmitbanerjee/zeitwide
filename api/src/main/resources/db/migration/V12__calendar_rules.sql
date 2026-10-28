-- Calendar rules as data: how weekend holidays move to weekdays, whether a calendar closes
-- on holidays at all (FedNow doesn't), and operating hours with cutoffs. The pipeline
-- syncs them from pipeline/src/settlecal_pipeline/calendars/catalog/.

ALTER TABLE calendar
    ADD COLUMN closes_on_holidays boolean NOT NULL DEFAULT true,
    ADD COLUMN observance_rule jsonb CHECK (observance_rule IS NULL OR jsonb_typeof(observance_rule) = 'object'),
    ADD COLUMN operating_hours jsonb CHECK (operating_hours IS NULL OR jsonb_typeof(operating_hours) = 'object');

-- Replaces V10's version: the pipeline may now insert calendars and update their rules.
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
        -- Calendar definitions are data files in the pipeline; it keeps their rows in step.
        EXECUTE format(
            'GRANT INSERT, UPDATE (name, kind, timezone, weekend_rule, closes_on_holidays,
                 observance_rule, operating_hours, current_revision)
             ON calendar TO %I', c_pipeline);
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
