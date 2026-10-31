-- API keys now start with zw_, after the project's rename from SettleCal. Stored prefixes
-- move with them. The secret part is unchanged, so the holder of sc_live_AbCd1234_<secret>
-- now presents zw_live_AbCd1234_<secret>; a key that still starts with sc_ is rejected.
ALTER TABLE api_key DROP CONSTRAINT api_key_prefix_check;

UPDATE api_key SET prefix = 'zw_' || substr(prefix, 4) WHERE prefix LIKE 'sc\_%';

ALTER TABLE api_key
    ADD CONSTRAINT api_key_prefix_check CHECK (prefix ~ '^zw_(live|test)_[A-Za-z0-9]{8}$');
