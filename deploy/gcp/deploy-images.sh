#!/usr/bin/env bash
# Builds the four images from this checkout, pushes them to Artifact Registry and rolls
# them out: the API first (it runs the Flyway migrations), then the pipeline job, the
# web app and Keycloak. Use it when CI can't deploy.
#
#   bash deploy/gcp/deploy-images.sh            # all four
#   bash deploy/gcp/deploy-images.sh api web    # only these
#
# Needs Docker, a JDK 17+ as JAVA_HOME, and `gcloud auth configure-docker
# us-central1-docker.pkg.dev` once.
set -euo pipefail

PROJECT="${PROJECT:-project-6eaae4e0-3164-4e31-bc4}"
ENVIRONMENT="${ENVIRONMENT:-dev}"
REGION="${REGION:-us-central1}"
REPOSITORY="$REGION-docker.pkg.dev/$PROJECT/zeitwide-$ENVIRONMENT"

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
# A -dirty suffix marks images built from uncommitted changes.
TAG="${TAG:-$(git -C "$ROOT" describe --always --dirty --abbrev=12)}"
if [ "$#" -gt 0 ]; then COMPONENTS=("$@"); else COMPONENTS=(api pipeline web keycloak); fi

if command -v gcloud.cmd >/dev/null 2>&1; then GCLOUD=gcloud.cmd; else GCLOUD=gcloud; fi
gcloud_run() { "$GCLOUD" run "$@" --project "$PROJECT" --region "$REGION" --quiet; }

build_and_push() {
  local name="$1" image="$REPOSITORY/$1:$TAG"
  echo "==> $name: building $image"
  case "$name" in
    api) (cd "$ROOT/api" && ./gradlew --quiet jibDockerBuild --image="$image") ;;
    pipeline) docker build --platform linux/amd64 -t "$image" "$ROOT/pipeline" ;;
    web)
      # Answer pages prerender from the API with the web app's own key, passed as a BuildKit
      # secret so it never lands in an image layer (deploy/firebase/README.md).
      local api_url key_file
      api_url="${ZEITWIDE_API_URL:-$(gcloud_run services describe "zeitwide-api-$ENVIRONMENT" --format='value(status.url)')}"
      key_file="$(mktemp)"
      "$GCLOUD" secrets versions access latest --secret "zeitwide-$ENVIRONMENT-web-api-key"         --project "$PROJECT" > "$key_file"
      DOCKER_BUILDKIT=1 docker build --platform linux/amd64 -t "$image"         --build-arg ZEITWIDE_API_URL="$api_url"         --build-arg SITE_URL="${SITE_URL:?set SITE_URL to the public web origin}"         --secret id=zeitwide_api_key,src="$key_file"         "$ROOT/web" || { rm -f "$key_file"; exit 1; }
      rm -f "$key_file"
      ;;
    keycloak) docker build --platform linux/amd64 -t "$image" "$ROOT/deploy/keycloak" ;;
    *) echo "Unknown component: $name (api, pipeline, web or keycloak)" >&2; exit 1 ;;
  esac
  docker push --quiet "$image"
}

roll_out() {
  local name="$1" image="$REPOSITORY/$1:$TAG"
  echo "==> $name: deploying"
  case "$name" in
    api | web) gcloud_run services update "zeitwide-$name-$ENVIRONMENT" --image="$image" ;;
    keycloak) gcloud_run services update "keycloak-$ENVIRONMENT" --image="$image" ;;
    pipeline) gcloud_run jobs update "zeitwide-pipeline-$ENVIRONMENT" --image="$image" ;;
  esac
}

for name in "${COMPONENTS[@]}"; do build_and_push "$name"; done
for name in "${COMPONENTS[@]}"; do roll_out "$name"; done
echo "Deployed $TAG: ${COMPONENTS[*]}"
