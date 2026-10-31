#!/usr/bin/env bash
# Renames the SettleCal database and logins on Aiven to Zeitwide's, keeping all data
# (rename-from-settlecal.sql). Run it once, before setup.sh, while nothing needs the old
# API: it closes the old database's connections.
#
#   bash deploy/gcp/aiven/rename-from-settlecal.sh
#
# Run it from Git Bash, signed in to gcloud, with Docker running. It asks for the avnadmin
# password at a hidden prompt, and never prints it or puts it on a command line.
set -euo pipefail

PROJECT="${PROJECT:-project-6eaae4e0-3164-4e31-bc4}"
ENVIRONMENT="${ENVIRONMENT:-dev}"
# The psql client matches the Aiven server (PostgreSQL 18).
PSQL_IMAGE="postgres:18.6-alpine"
HERE="$(cd "$(dirname "$0")" && pwd)"

if command -v gcloud.cmd >/dev/null 2>&1; then GCLOUD=gcloud.cmd; else GCLOUD=gcloud; fi

secret() {
  "$GCLOUD" secrets versions access latest --secret "zeitwide-$ENVIRONMENT-$1" --project "$PROJECT"
}

CA_FILE="$(mktemp)"
trap 'rm -f "$CA_FILE"' EXIT
secret db-ca-cert > "$CA_FILE"
grep -q "BEGIN CERTIFICATE" "$CA_FILE" || { echo "zeitwide-$ENVIRONMENT-db-ca-cert is not a PEM" >&2; exit 1; }

export DB_HOST DB_PORT PGPASSWORD
DB_HOST="$(secret db-host)"
DB_PORT="$(secret db-port)"
read -rsp "avnadmin password for $DB_HOST: " PGPASSWORD
echo

# Docker on Windows needs a Windows path for the mount.
CA_MOUNT="$(cd "$(dirname "$CA_FILE")" && pwd -W 2>/dev/null || pwd)/$(basename "$CA_FILE")"

# -e NAME passes each value from this shell's environment, not on the command line.
docker run --rm -i \
  -v "$CA_MOUNT:/ca.pem:ro" \
  -e DB_HOST -e DB_PORT -e PGPASSWORD \
  "$PSQL_IMAGE" sh -c '
    psql "host=$DB_HOST port=$DB_PORT user=avnadmin dbname=defaultdb sslmode=verify-full sslrootcert=/ca.pem" \
      -f /dev/stdin
  ' < "$HERE/rename-from-settlecal.sql"
unset PGPASSWORD
echo "Done. Run setup.sh next only if the output above shows a role or database missing."
