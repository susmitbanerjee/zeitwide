#!/bin/sh
# Creates the Keycloak role and database next to the SettleCal database.
# Runs once, when the Postgres data volume is first initialised.
set -eu

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v kc_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE ROLE keycloak LOGIN PASSWORD :'kc_password';
CREATE DATABASE keycloak OWNER keycloak;
SQL
