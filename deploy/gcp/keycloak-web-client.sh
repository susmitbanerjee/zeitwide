#!/usr/bin/env bash
# Points the zeitwide-web client's redirect URIs, web origins and post-logout URIs at the
# web app's sign-in host. Keycloak imports realm.json only when the realm doesn't exist, so a
# change of web_app_origin needs this on a running realm.
#
#   bash deploy/gcp/keycloak-web-client.sh https://app.zeitwide.com [https://other-origin ...]
#
# Run it from Git Bash, signed in to gcloud. The admin password comes from Secret Manager and
# is never printed or put on a command line.
set -euo pipefail

PROJECT="${PROJECT:-project-6eaae4e0-3164-4e31-bc4}"
ENVIRONMENT="${ENVIRONMENT:-dev}"
REGION="${REGION:-us-central1}"
REALM="zeitwide"
[ "$#" -ge 1 ] || { echo "Usage: keycloak-web-client.sh ORIGIN [ORIGIN ...]" >&2; exit 1; }
for origin in "$@"; do
  [[ "$origin" =~ ^https://[^/]+$ ]] || { echo "Not an https origin: $origin" >&2; exit 1; }
done

if command -v gcloud.cmd >/dev/null 2>&1; then GCLOUD=gcloud.cmd; else GCLOUD=gcloud; fi
KEYCLOAK="${KEYCLOAK_URL:-$("$GCLOUD" run services describe "keycloak-$ENVIRONMENT" \
  --project "$PROJECT" --region "$REGION" --format='value(status.url)')}"

ADMIN_PASSWORD="$("$GCLOUD" secrets versions access latest \
  --secret "zeitwide-$ENVIRONMENT-keycloak-admin-password" --project "$PROJECT")"
TOKEN="$(curl -fsS "$KEYCLOAK/realms/master/protocol/openid-connect/token" \
  --data-urlencode grant_type=password --data-urlencode client_id=admin-cli \
  --data-urlencode username=bootstrap-admin --data-urlencode "password@-" <<< "$ADMIN_PASSWORD" |
  python -I -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')"
unset ADMIN_PASSWORD

ADMIN="$KEYCLOAK/admin/realms/$REALM"
CLIENT_ID="$(curl -fsS -H "Authorization: Bearer $TOKEN" "$ADMIN/clients?clientId=zeitwide-web" |
  python -I -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])')"

curl -fsS -H "Authorization: Bearer $TOKEN" "$ADMIN/clients/$CLIENT_ID" |
  python -I -c '
import json, sys
client = json.load(sys.stdin)
origins = sys.argv[1:]
client["redirectUris"] = [f"{o}/*" for o in origins]
client["webOrigins"] = origins
client.setdefault("attributes", {})["post.logout.redirect.uris"] = "##".join(f"{o}/*" for o in origins)
print(json.dumps(client))
' "$@" |
  curl -fsS -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    --data-binary @- "$ADMIN/clients/$CLIENT_ID"

echo "zeitwide-web now accepts sign-in callbacks for: $*"
