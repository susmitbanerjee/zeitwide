#!/usr/bin/env bash
# Sets the OAuth client ID and secret of a Keycloak identity provider (google, microsoft or
# github) on the running realm. Keycloak imports realm.json only when the realm doesn't exist,
# so adding a client ID to the tfvars later doesn't reach a realm that is already running.
#
#   bash deploy/gcp/add-secret.sh google-oauth-client-secret        # once, hidden prompt
#   bash deploy/gcp/keycloak-identity-provider.sh google CLIENT_ID
#
# Run it from Git Bash, signed in to gcloud. Both the admin password and the provider's
# secret come from Secret Manager and are never printed or put on a command line.
set -euo pipefail

PROJECT="${PROJECT:-project-6eaae4e0-3164-4e31-bc4}"
ENVIRONMENT="${ENVIRONMENT:-dev}"
REGION="${REGION:-us-central1}"
REALM="zeitwide"
PROVIDER="${1:-}"
OAUTH_CLIENT_ID="${2:-}"
case "$PROVIDER" in google | microsoft | github) ;; *)
  echo "Usage: keycloak-identity-provider.sh google|microsoft|github CLIENT_ID" >&2
  exit 1
  ;;
esac
[ -n "$OAUTH_CLIENT_ID" ] || { echo "Usage: keycloak-identity-provider.sh $PROVIDER CLIENT_ID" >&2; exit 1; }

if command -v gcloud.cmd >/dev/null 2>&1; then GCLOUD=gcloud.cmd; else GCLOUD=gcloud; fi
KEYCLOAK="${KEYCLOAK_URL:-$("$GCLOUD" run services describe "keycloak-$ENVIRONMENT" \
  --project "$PROJECT" --region "$REGION" --format='value(status.url)')}"

ADMIN_PASSWORD="$("$GCLOUD" secrets versions access latest \
  --secret "zeitwide-$ENVIRONMENT-keycloak-admin-password" --project "$PROJECT")"
TOKEN="$(curl -fsS "$KEYCLOAK/realms/master/protocol/openid-connect/token" \
  --data-urlencode grant_type=password --data-urlencode client_id=admin-cli \
  --data-urlencode username=bootstrap-admin --data-urlencode "password@-" < <(printf '%s' "$ADMIN_PASSWORD") |
  python -I -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')"
unset ADMIN_PASSWORD

IDP_SECRET="$("$GCLOUD" secrets versions access latest \
  --secret "zeitwide-$ENVIRONMENT-$PROVIDER-oauth-client-secret" --project "$PROJECT")"
[ -n "$IDP_SECRET" ] || { echo "The $PROVIDER client secret is empty; add it with add-secret.sh first." >&2; exit 1; }

ADMIN="$KEYCLOAK/admin/realms/$REALM/identity-provider/instances/$PROVIDER"
curl -fsS -H "Authorization: Bearer $TOKEN" "$ADMIN" |
  IDP_SECRET="$IDP_SECRET" python -I -c '
import json, os, sys
idp = json.load(sys.stdin)
idp["enabled"] = True
idp["config"]["clientId"] = sys.argv[1]
idp["config"]["clientSecret"] = os.environ["IDP_SECRET"]
print(json.dumps(idp))
' "$OAUTH_CLIENT_ID" |
  curl -fsS -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    --data-binary @- "$ADMIN"
unset IDP_SECRET

echo "Keycloak's $PROVIDER sign-in now uses client $OAUTH_CLIENT_ID. Its callback URL is:"
echo "  $KEYCLOAK/realms/$REALM/broker/$PROVIDER/endpoint"
