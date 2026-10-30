-- Zeitwide's logins and databases on Aiven. Run by setup.sh as avnadmin; safe to re-run.
-- Passwords arrive as psql variables, read from Secret Manager by setup.sh.
\set ON_ERROR_STOP on

SELECT format('CREATE ROLE %I LOGIN', name)
FROM (VALUES ('zeitwide_api'), ('zeitwide_pipeline'), ('zeitwide_backup'), ('keycloak')) AS roles (name)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = roles.name)
\gexec

ALTER ROLE zeitwide_api PASSWORD :'api_password';
ALTER ROLE zeitwide_pipeline PASSWORD :'pipeline_password';
ALTER ROLE zeitwide_backup PASSWORD :'backup_password';
ALTER ROLE keycloak PASSWORD :'keycloak_password';

-- Making a role a database owner, or acting as it below, needs membership in it. On
-- PostgreSQL 16 and later avnadmin gets the right to act as the role without inheriting
-- its privileges.
SELECT current_setting('server_version_num')::int >= 160000 AS has_set_option \gset
\if :has_set_option
GRANT zeitwide_api, keycloak TO avnadmin WITH INHERIT FALSE, SET TRUE;
\else
GRANT zeitwide_api, keycloak TO avnadmin;
\endif

SELECT format('CREATE DATABASE %I OWNER %I', db, owner)
FROM (VALUES ('zeitwide', 'zeitwide_api'), ('keycloak', 'keycloak')) AS dbs (db, owner)
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = dbs.db)
\gexec

-- The API runs Flyway, which creates every table and the grants for the pipeline and
-- backup logins (V9 onwards).
ALTER DATABASE zeitwide OWNER TO zeitwide_api;
ALTER DATABASE keycloak OWNER TO keycloak;

-- Keycloak creates its own tables on first start; let the backup login read them. This
-- runs as keycloak, which owns the database and its public schema.
\connect keycloak
SET ROLE keycloak;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO zeitwide_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON SEQUENCES TO zeitwide_backup;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO zeitwide_backup;
RESET ROLE;

SELECT rolname, rolcanlogin FROM pg_roles
WHERE rolname IN ('zeitwide_api', 'zeitwide_pipeline', 'zeitwide_backup', 'keycloak')
ORDER BY rolname;
SELECT datname, pg_get_userbyid(datdba) AS owner FROM pg_database
WHERE datname IN ('zeitwide', 'keycloak') ORDER BY datname;
