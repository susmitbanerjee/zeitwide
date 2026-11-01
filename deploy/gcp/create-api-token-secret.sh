#!/usr/bin/env bash
# Creates the secret the web app and the API share (api-token-secret) with a random value,
# and hands the empty container to OpenTofu. Run it once per environment BEFORE the apply that
# first references it: Cloud Run won't start a revision whose secret has no value.
#
#   bash deploy/gcp/create-api-token-secret.sh
#
# The value is generated here, goes straight to Secret Manager and is never printed.
# Run it from Git Bash, signed in to gcloud, with `tofu workspace select <env>` already done.
set -euo pipefail

PROJECT="${PROJECT:-project-6eaae4e0-3164-4e31-bc4}"
ENVIRONMENT="${ENVIRONMENT:-dev}"
REGION="${REGION:-us-central1}"
SECRET="zeitwide-$ENVIRONMENT-api-token-secret"
HERE="$(cd "$(dirname "$0")" && pwd)"

if command -v gcloud.cmd >/dev/null 2>&1; then GCLOUD=gcloud.cmd; else GCLOUD=gcloud; fi

if "$GCLOUD" secrets describe "$SECRET" --project "$PROJECT" >/dev/null 2>&1; then
  echo "$SECRET already exists; leaving it alone."
else
  "$GCLOUD" secrets create "$SECRET" --project "$PROJECT" \
    --replication-policy=user-managed --locations="$REGION"
  # 48 random bytes, base64: 64 characters, well over the API's 32-character minimum.
  openssl rand -base64 48 | tr -d '\n' | "$GCLOUD" secrets versions add "$SECRET" \
    --project "$PROJECT" --data-file=-
fi

cd "$HERE"
if tofu state list 2>/dev/null | grep -q 'api-token-secret"\]$'; then
  echo "OpenTofu already tracks $SECRET."
else
  tofu import -var-file="envs/$ENVIRONMENT.tfvars" \
    'google_secret_manager_secret.this["api-token-secret"]' "projects/$PROJECT/secrets/$SECRET"
fi
echo "Now run: tofu apply -var-file=envs/$ENVIRONMENT.tfvars"
