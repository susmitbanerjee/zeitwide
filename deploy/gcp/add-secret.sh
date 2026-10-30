#!/usr/bin/env bash
# Adds a value to one of Zeitwide's secrets, typed at a hidden prompt.
#
#   bash deploy/gcp/add-secret.sh github-release-token
#
# The value is never echoed, written to a file, put on a command line or saved in shell
# history, and it is stored without a trailing newline.
set -euo pipefail

PROJECT="${PROJECT:-project-6eaae4e0-3164-4e31-bc4}"
ENVIRONMENT="${ENVIRONMENT:-dev}"
NAME="${1:?Usage: add-secret.sh SECRET (for example github-release-token)}"
SECRET="zeitwide-$ENVIRONMENT-$NAME"

if command -v gcloud.cmd >/dev/null 2>&1; then GCLOUD=gcloud.cmd; else GCLOUD=gcloud; fi

read -rsp "Value for $SECRET: " VALUE
echo
[ -n "$VALUE" ] || { echo "Nothing entered; $SECRET is unchanged." >&2; exit 1; }
printf '%s' "$VALUE" | "$GCLOUD" secrets versions add "$SECRET" --data-file=- --project "$PROJECT"
unset VALUE
