-- Housekeeping that keeps the database well under the 1 GB of Aiven's free plan.
-- The pipeline's daily job calls roll_up_usage(date_trunc('day', now(), 'UTC'))
-- and then cleanup_old_rows().

-- Recomputes usage_daily for every whole UTC day before p_until that still has raw
-- rows. Re-running it gives the same result.
CREATE FUNCTION roll_up_usage(p_until timestamptz) RETURNS bigint
LANGUAGE sql
AS $$
    WITH upserted AS (
        INSERT INTO usage_daily (account_id, usage_date, feature, request_count)
        SELECT account_id, (occurred_at AT TIME ZONE 'UTC')::date, feature, count(*)
        FROM usage_event
        WHERE occurred_at < date_trunc('day', p_until, 'UTC')
        GROUP BY 1, 2, 3
        ON CONFLICT (account_id, usage_date, feature)
            DO UPDATE SET request_count = EXCLUDED.request_count
        RETURNING 1
    )
    SELECT count(*) FROM upserted;
$$;

-- Deletes job-queue, webhook-delivery and raw usage rows older than p_retention
-- (whole UTC days), after rolling the usage up, plus expired rate-limit buckets.
CREATE FUNCTION cleanup_old_rows(p_retention interval DEFAULT interval '30 days')
RETURNS TABLE (table_name text, deleted_rows bigint)
LANGUAGE plpgsql
AS $$
DECLARE
    v_cutoff timestamptz;
BEGIN
    IF p_retention < interval '1 day' THEN
        RAISE EXCEPTION 'retention must be at least one day, got %', p_retention
            USING ERRCODE = 'invalid_parameter_value';
    END IF;
    v_cutoff := date_trunc('day', now() - p_retention, 'UTC');

    PERFORM roll_up_usage(v_cutoff);

    DELETE FROM usage_event WHERE occurred_at < v_cutoff;
    GET DIAGNOSTICS deleted_rows = ROW_COUNT;
    table_name := 'usage_event';
    RETURN NEXT;

    -- Retries stop after 24 hours, so every row this old is finished.
    DELETE FROM webhook_delivery WHERE created_at < v_cutoff;
    GET DIAGNOSTICS deleted_rows = ROW_COUNT;
    table_name := 'webhook_delivery';
    RETURN NEXT;

    DELETE FROM rate_limit_bucket
    WHERE expires_at < (extract(epoch FROM now()) * 1000)::bigint;
    GET DIAGNOSTICS deleted_rows = ROW_COUNT;
    table_name := 'rate_limit_bucket';
    RETURN NEXT;

    -- Procrastinate owns its tables and may not be installed yet, so look them up at
    -- run time. Only finished jobs with no recent event are removed (its events
    -- cascade with the job).
    IF to_regclass('procrastinate_jobs') IS NOT NULL
       AND to_regclass('procrastinate_events') IS NOT NULL THEN
        EXECUTE $sql$
            DELETE FROM procrastinate_jobs AS job
            WHERE job.status::text IN ('succeeded', 'failed', 'cancelled', 'aborted')
              AND NOT EXISTS (
                  SELECT 1 FROM procrastinate_events AS event
                  WHERE event.job_id = job.id AND event.at >= $1)
        $sql$ USING v_cutoff;
        GET DIAGNOSTICS deleted_rows = ROW_COUNT;
        table_name := 'procrastinate_jobs';
        RETURN NEXT;
    END IF;
END;
$$;
