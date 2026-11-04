-- Where a new account came from, when an AI assistant referred the visit that led to sign-up:
-- 'ai:<vendor>' and nothing else (the edge Worker's zw_ref cookie). Null for everyone else. It
-- names a vendor only, never a person, a page or a query.
ALTER TABLE account
    ADD COLUMN signup_source text
        CHECK (signup_source ~ '^ai:(openai|perplexity|anthropic|google|microsoft)$');
