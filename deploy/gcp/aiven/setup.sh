#!/usr/bin/env bash
# Creates SettleCal's logins and databases on Aiven and stores Aiven's CA certificate.
#
#   bash deploy/gcp/aiven/setup.sh path/to/ca.pem
#
# Run it from Git Bash, signed in to gcloud, with Docker running. It asks for the
# avnadmin password at a hidden prompt. The login passwords come from Secret Manager.
# None of them is printed, written to a file or put on a command line.
set -euo pipefail

PROJECT="${PROJECT:-project-6eaae4e0-3164-4e31-bc4}"
ENVIRONMENT="${ENVIRONMENT:-dev}"
# The psql client matches the Aiven server (PostgreSQL 18).
PSQL_IMAGE="postgres:18.6-alpine"
HERE="$(cd "$(dirname "$0")" && pwd)"
CA_FILE="${1:?Usage: setup.sh path/to/ca.pem (Aiven console > Overview > CA certificate)}"

if command -v gcloud.cmd >/dev/null 2>&1; then GCLOUD=gcloud.cmd; else GCLOUD=gcloud; fi

secret() {
  "$GCLOUD" secrets versions access latest --secret "settlecal-$ENVIRONMENT-$1" --project "$PROJECT"
}

[ -s "$CA_FILE" ] || { echo "No CA certificate at $CA_FILE" >&2; exit 1; }
grep -q "BEGIN CERTIFICATE" "$CA_FILE" || { echo "$CA_FILE is not a PEM certificate" >&2; exit 1; }

export DB_HOST DB_PORT API_PASSWORD PIPELINE_PASSWORD BACKUP_PASSWORD KEYCLOAK_PASSWORD PGPASSWORD
DB_HOST="$(secret db-host)"
DB_PORT="$(secret db-port)"
API_PASSWORD="$(secret db-api-password)"
PIPELINE_PASSWORD="$(secret db-pipeline-password)"
BACKUP_PASSWORD="$(secret db-backup-password)"
KEYCLOAK_PASSWORD="$(secret db-keycloak-password)"
read -rsp "avnadmin password for $DB_HOST: " PGPASSWORD
echo

# Docker on Windows needs a Windows path for the mount.
CA_MOUNT="$(cd "$(dirname "$CA_FILE")" && pwd -W 2>/dev/null || pwd)/$(basename "$CA_FILE")"

# -e NAME passes each value from this shell's environment, not on the command line.
docker run --rm -i \
  -v "$CA_MOUNT:/ca.pem:ro" \
  -e DB_HOST -e DB_PORT -e PGPASSWORD \
  -e API_PASSWORD -e PIPELINE_PASSWORD -e BACKUP_PASSWORD -e KEYCLOAK_PASSWORD \
  "$PSQL_IMAGE" sh -c '
    psql "host=$DB_HOST port=$DB_PORT user=avnadmin dbname=defaultdb sslmode=verify-full sslrootcert=/ca.pem" \
      -v api_password="$API_PASSWORD" -v pipeline_password="$PIPELINE_PASSWORD" \
      -v backup_password="$BACKUP_PASSWORD" -v keycloak_password="$KEYCLOAK_PASSWORD" \
      -f /dev/stdin
  ' < "$HERE/setup.sql"
unset PGPASSWORD API_PASSWORD PIPELINE_PASSWORD BACKUP_PASSWORD KEYCLOAK_PASSWORD

# The connection above already proved this CA verifies Aiven's certificate and hostname.
if "$GCLOUD" secrets versions list "settlecal-$ENVIRONMENT-db-ca-cert" --project "$PROJECT" \
    --filter="state=ENABLED" --format="value(name)" | grep -q .; then
  echo "settlecal-$ENVIRONMENT-db-ca-cert already has a version; left as it is."
else
  "$GCLOUD" secrets versions add "settlecal-$ENVIRONMENT-db-ca-cert" --data-file="$CA_FILE" --project "$PROJECT"
fi
echo "Done. You can delete $CA_FILE; it is in Secret Manager."
