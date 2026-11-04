#!/usr/bin/env bash
# The acceptance matrix: each request, what body it must get, and (where the Worker exposes them)
# its labels. Run it against `npm run dev` (labels in x-zw-edge, origin = the live site) or
# against the deployed zone (no debug header: check labels with the SQL in ../README.md).
#
#   scripts/curl-matrix.sh                       # http://localhost:8787
#   scripts/curl-matrix.sh https://zeitwide.com
#   RATE=1 scripts/curl-matrix.sh                # also send 61 GPTBot requests and expect a 429
#   CURL_EXTRA="--resolve zeitwide.com:443:<cloudflare ip>" scripts/curl-matrix.sh https://zeitwide.com
#                                                # pin to the edge while resolvers still cache old DNS
set -euo pipefail

BASE="${1:-http://localhost:8787}"
PAGE="${PAGE:-/calendars/GB-CHAPS}"
BROWSER_UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
BROWSER_ACCEPT="text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
GPTBOT_UA="Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; GPTBot/1.3; +https://openai.com/gptbot)"
failures=0

# check NAME EXPECTED_TYPE BODY_PREFIX EXPECTED_LABELS -- curl args...
check() {
  local name="$1" want_type="$2" want_body="$3" want_labels="$4"
  shift 4
  local headers body
  headers="$(mktemp)"
  body="$(curl -sS --compressed ${CURL_EXTRA:-} -D "$headers" -o - "$@")"
  local status type vary labels
  status="$(awk 'toupper($1) ~ /^HTTP/ {code=$2} END {print code}' "$headers")"
  type="$(grep -i '^content-type:' "$headers" | tail -1 | cut -d' ' -f2- | tr -d '\r')"
  vary="$(grep -i '^vary:' "$headers" | cut -d' ' -f2- | tr -d '\r' | paste -sd, -)"
  labels="$(grep -i '^x-zw-edge:' "$headers" | cut -d' ' -f2- | tr -d '\r' || true)"
  rm -f "$headers"
  local ok=1
  [[ "$status" == "200" ]] || ok=0
  [[ "$type" == "$want_type"* ]] || ok=0
  [[ "$body" == "$want_body"* ]] || ok=0
  [[ ",$(tr -d ' ' <<<"${vary,,}")," == *",accept,"* ]] || ok=0
  if [[ -n "$labels" && "$labels" != "$want_labels" ]]; then ok=0; fi
  printf '%-34s %s  %s  %-26s vary=[%s]  %s\n' "$name" "$([[ $ok == 1 ]] && echo PASS || echo FAIL)" \
    "$status" "${type%%;*}" "$vary" "${labels:-(labels: see Analytics Engine)}"
  [[ $ok == 1 ]] || failures=$((failures + 1))
}

check "browser" "text/html" "<!DOCTYPE html>" \
  "family=human; vendor=-; verified=false; markdown=false" \
  -H "user-agent: $BROWSER_UA" -H "accept: $BROWSER_ACCEPT" "$BASE$PAGE"

check "GPTBot UA, non-OpenAI IP" "text/html" "<!DOCTYPE html>" \
  "family=training; vendor=openai; verified=false; markdown=false" \
  -H "user-agent: $GPTBOT_UA" "$BASE$PAGE"

check "Accept: text/markdown" "text/markdown" "# " \
  "family=unknown; vendor=unknown; verified=false; markdown=true" \
  -H "user-agent: curl/8" -H "accept: text/markdown, text/html;q=0.5" "$BASE$PAGE"

if [[ "${RATE:-0}" == "1" ]]; then
  limited=0
  for _ in $(seq 1 61); do
    code="$(curl -s -o /dev/null -w '%{http_code}' -H "user-agent: $GPTBOT_UA" "$BASE/sources")"
    [[ "$code" == "429" ]] && limited=$((limited + 1))
  done
  printf '%-34s %s  %d of 61 answered 429\n' "unverified bot, 61 in a minute" \
    "$([[ $limited -ge 1 ]] && echo PASS || echo FAIL)" "$limited"
  [[ $limited -ge 1 ]] || failures=$((failures + 1))
fi

exit "$failures"
