-- Renames an existing SettleCal database and its logins to Zeitwide's names, in place, so
-- every table, entry version, release, account and grant comes along. Run once by
-- rename-from-settlecal.sh as avnadmin, before setup.sh: setup.sh would otherwise create
-- an empty zeitwide database. Safe to re-run: it does nothing once the rename is done.
\set ON_ERROR_STOP on

SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'settlecal') AS has_old,
       EXISTS (SELECT 1 FROM pg_database WHERE datname = 'zeitwide') AS has_new \gset

\if :has_new
  \if :has_old
    \echo 'Both settlecal and zeitwide databases exist. Move the data by hand; nothing changed.'
    \quit
  \endif
  \echo 'Already renamed: the zeitwide database exists and settlecal does not.'
  \quit
\endif
\if :has_old
\else
  \echo 'No settlecal database to rename; run setup.sh for a fresh Zeitwide database.'
  \quit
\endif

-- Renamed roles keep their grants, the objects they own and their SCRAM passwords, so the
-- Secret Manager passwords stay valid. (Only MD5 passwords are cleared by a rename.)
SELECT format('ALTER ROLE %I RENAME TO %I', old, new)
FROM (VALUES ('settlecal_api', 'zeitwide_api'),
             ('settlecal_pipeline', 'zeitwide_pipeline'),
             ('settlecal_backup', 'zeitwide_backup')) AS roles (old, new)
WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = roles.old)
  AND NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = roles.new)
\gexec

-- Renaming needs the owner's rights plus CREATEDB. avnadmin may act as the owner (setup.sql
-- granted SET) but doesn't inherit ownership, so the owner gets CREATEDB for the rename
-- only. As the owner, shut out new connections and close the old API's, then rename.
-- The pipeline must be idle (its Scheduler jobs paused): its sessions can't be closed here.
ALTER ROLE zeitwide_api CREATEDB;
SET ROLE zeitwide_api;
ALTER DATABASE settlecal WITH ALLOW_CONNECTIONS false;
SELECT count(pg_terminate_backend(pid)) AS closed_connections
FROM pg_stat_activity
WHERE datname = 'settlecal' AND usename = current_user AND pid <> pg_backend_pid();
ALTER DATABASE settlecal RENAME TO zeitwide;
ALTER DATABASE zeitwide WITH ALLOW_CONNECTIONS true;
RESET ROLE;
ALTER ROLE zeitwide_api NOCREATEDB;

SELECT rolname FROM pg_roles WHERE rolname LIKE 'zeitwide\_%' OR rolname LIKE 'settlecal\_%'
ORDER BY rolname;
SELECT datname, pg_get_userbyid(datdba) AS owner FROM pg_database
WHERE datname IN ('zeitwide', 'settlecal', 'keycloak') ORDER BY datname;
