-- SettleCal's logins and databases on Aiven. Run by setup.sh as avnadmin; safe to re-run.
-- Passwords arrive as psql variables, read from Secret Manager by setup.sh.
\set ON_ERROR_STOP on

SELECT format('CREATE ROLE %I LOGIN', name)
FROM (VALUES ('settlecal_api'), ('settlecal_pipeline'), ('settlecal_backup'), ('keycloak')) AS roles (name)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = roles.name)
\gexec

ALTER ROLE settlecal_api PASSWORD :'api_password';
ALTER ROLE settlecal_pipeline PASSWORD :'pipeline_password';
ALTER ROLE settlecal_backup PASSWORD :'backup_password';
ALTER ROLE keycloak PASSWORD :'keycloak_password';

-- Making a role a database owner, or acting as it below, needs membership in it. On
-- PostgreSQL 16 and later avnadmin gets the right to act as the role without inheriting
-- its privileges.
SELECT current_setting('server_version_num')::int >= 160000 AS has_set_option \gset
\if :has_set_option
GRANT settlecal_api, keycloak TO avnadmin WITH INHERIT FALSE, SET TRUE;
\else
GRANT settlecal_api, keycloak TO avnadmin;
\endif

SELECT format('CREATE DATABASE %I OWNER %I', db, owner)
FROM (VALUES ('settlecal', 'settlecal_api'), ('keycloak', 'keycloak')) AS dbs (db, owner)
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = dbs.db)
\gexec

-- The API runs Flyway, which creates every table and the grants for the pipeline and
-- backup logins (V9 onwards).
ALTER DATABASE settlecal OWNER TO settlecal_api;
ALTER DATABASE keycloak OWNER TO keycloak;

-- Keycloak creates its own tables on first start; let the backup login read them. This
-- runs as keycloak, which owns the database and its public schema.
\connect keycloak
SET ROLE keycloak;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO settlecal_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON SEQUENCES TO settlecal_backup;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO settlecal_backup;
RESET ROLE;

SELECT rolname, rolcanlogin FROM pg_roles
WHERE rolname IN ('settlecal_api', 'settlecal_pipeline', 'settlecal_backup', 'keycloak')
ORDER BY rolname;
SELECT datname, pg_get_userbyid(datdba) AS owner FROM pg_database
WHERE datname IN ('settlecal', 'keycloak') ORDER BY datname;
