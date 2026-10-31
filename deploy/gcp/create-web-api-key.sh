#!/usr/bin/env bash
# Creates the web app's own API key: an account and a key row in the database, with the key
# itself stored only in Secret Manager (zeitwide-<env>-web-api-key). The web app prerenders
# answer pages with it, and the API gives it the first-party limit once its ID is listed in
# first_party_api_key_ids. Run it once per environment; --rotate replaces the key.
#
#   bash deploy/gcp/create-web-api-key.sh [--rotate]
#
# Run it from Git Bash, signed in to gcloud, with Docker running, after `tofu apply` has
# created the secret. The key is never printed, written to a file or put on a command line.
set -euo pipefail

PROJECT="${PROJECT:-project-6eaae4e0-3164-4e31-bc4}"
ENVIRONMENT="${ENVIRONMENT:-dev}"
PSQL_IMAGE="postgres:18.6-alpine"
SECRET="zeitwide-$ENVIRONMENT-web-api-key"
ROTATE="${1:-}"

if command -v gcloud.cmd >/dev/null 2>&1; then GCLOUD=gcloud.cmd; else GCLOUD=gcloud; fi

secret() {
  "$GCLOUD" secrets versions access latest --secret "zeitwide-$ENVIRONMENT-$1" --project "$PROJECT"
}

if "$GCLOUD" secrets versions list "$SECRET" --project "$PROJECT" --filter="state=ENABLED" \
    --format="value(name)" | grep -q . && [ "$ROTATE" != "--rotate" ]; then
  echo "$SECRET already has a key. Pass --rotate to replace it." >&2
  exit 1
fi

random() { head -c 4096 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9' | cut -c1-"$1"; }
export KEY_PREFIX KEY_SHA256
KEY_PREFIX="zw_live_$(random 8)"
KEY_SECRET="$(random 43)"
KEY_SHA256="$(printf '%s' "$KEY_SECRET" | sha256sum | cut -c1-64)"

CA_FILE="$(mktemp)"
trap 'rm -f "$CA_FILE"' EXIT
secret db-ca-cert > "$CA_FILE"
CA_MOUNT="$(cd "$(dirname "$CA_FILE")" && pwd -W 2>/dev/null || pwd)/$(basename "$CA_FILE")"

export DB_HOST DB_PORT PGPASSWORD
DB_HOST="$(secret db-host)"
DB_PORT="$(secret db-port)"
PGPASSWORD="$(secret db-api-password)"

# The account and key the API would write; only the SHA-256 of the secret part is stored.
KEY_ID="$(docker run --rm -i -v "$CA_MOUNT:/ca.pem:ro" -e DB_HOST -e DB_PORT -e PGPASSWORD \
  -e KEY_PREFIX -e KEY_SHA256 "$PSQL_IMAGE" sh -c '
    psql "host=$DB_HOST port=$DB_PORT user=zeitwide_api dbname=zeitwide sslmode=verify-full sslrootcert=/ca.pem" \
      -v ON_ERROR_STOP=1 -qtA -v prefix="$KEY_PREFIX" -v sha="$KEY_SHA256" -f /dev/stdin
  ' <<'SQL'
WITH web AS (
  INSERT INTO account (name) VALUES ('Zeitwide web app') RETURNING id
)
INSERT INTO api_key (account_id, name, prefix, secret_sha256)
SELECT web.id, 'zeitwide-web prerendering', :'prefix', decode(:'sha', 'hex') FROM web
RETURNING id;
SQL
)"
unset PGPASSWORD

printf '%s_%s' "$KEY_PREFIX" "$KEY_SECRET" |
  "$GCLOUD" secrets versions add "$SECRET" --data-file=- --project "$PROJECT" >/dev/null
unset KEY_SECRET

echo "Stored the new key in $SECRET."
echo "Add its ID to first_party_api_key_ids in envs/$ENVIRONMENT.tfvars: $KEY_ID"
if [ "$ROTATE" = "--rotate" ]; then
  echo "Then revoke the old key: UPDATE api_key SET revoked_at = now() WHERE name = 'zeitwide-web prerendering' AND id <> '$KEY_ID';"
fi
