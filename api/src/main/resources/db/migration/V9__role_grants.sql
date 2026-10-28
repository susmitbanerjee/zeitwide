-- Least-privilege access for the pipeline and backup logins (deploy/gcp/README.md,
-- step 5). The API's login owns the schema and runs these migrations.

-- These functions change rows the pipeline may not touch directly, so they run with
-- their owner's rights. The pinned search_path stops a caller's objects from being
-- picked up in their place.
ALTER FUNCTION publish_entry_version(text, date, boolean, text, text, bigint, timestamptz)
    SECURITY DEFINER SET search_path = public, pg_temp;
ALTER FUNCTION roll_up_usage(timestamptz)
    SECURITY DEFINER SET search_path = public, pg_temp;
ALTER FUNCTION cleanup_old_rows(interval)
    SECURITY DEFINER SET search_path = public, pg_temp;

-- PostgreSQL lets every role execute a new function; only granted roles may run these.
REVOKE EXECUTE ON FUNCTION publish_entry_version(text, date, boolean, text, text, bigint, timestamptz)
    FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION roll_up_usage(timestamptz) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION cleanup_old_rows(interval) FROM PUBLIC;

-- Grants what each login needs, for the logins that exist. The afterMigrate callback
-- runs it on every migrate, so a login created after the first deploy gets its grants
-- on the next one. Returns the logins it granted to.
--
-- The pipeline writes evidence, proposals, changes and releases, and changes entries
-- only through publish_entry_version. Add a new pipeline table here with
-- CREATE OR REPLACE in a later migration. The backup login reads every table
-- (current and future) for pg_dump.
CREATE FUNCTION apply_role_grants() RETURNS SETOF text
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
DECLARE
    -- Login names match deploy/gcp/locals.tf.
    c_pipeline CONSTANT text := 'settlecal_pipeline';
    c_backup   CONSTANT text := 'settlecal_backup';
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = c_pipeline) THEN
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), c_pipeline);
        EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', c_pipeline);
        EXECUTE format('GRANT SELECT ON calendar, source, calendar_entry TO %I', c_pipeline);
        EXECUTE format('GRANT UPDATE (current_revision) ON calendar TO %I', c_pipeline);
        EXECUTE format(
            'GRANT SELECT, INSERT ON source_snapshot, observation, candidate_change,
                 candidate_observation, entry_source, change_event, outbox, dataset_release
             TO %I', c_pipeline);
        EXECUTE format(
            'GRANT UPDATE (status, reviewer_subject, reviewed_at) ON candidate_change TO %I',
            c_pipeline);
        EXECUTE format(
            'GRANT EXECUTE ON FUNCTION
                 publish_entry_version(text, date, boolean, text, text, bigint, timestamptz),
                 roll_up_usage(timestamptz),
                 cleanup_old_rows(interval)
             TO %I', c_pipeline);
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

REVOKE EXECUTE ON FUNCTION apply_role_grants() FROM PUBLIC;
