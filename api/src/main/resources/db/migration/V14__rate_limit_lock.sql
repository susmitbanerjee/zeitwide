-- Bucket4j's advisory-lock backend stores each bucket's lock ID next to its state, so its
-- expiry clean-up can take the same lock as a request using the bucket.
ALTER TABLE rate_limit_bucket ADD COLUMN explicit_lock bigint;
