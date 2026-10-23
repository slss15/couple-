#!/usr/bin/env bash
# Trigger a push-deploy on the home server and wait for its result.
#
#   deploy-hook.sh <service> <image> <digest> [sha]
#
# Env:
#   DEPLOY_HOOK_URL     base URL, e.g. https://couple.example.com   (required)
#   DEPLOY_HOOK_SECRET  shared HMAC secret                         (required)
#   DEPLOY_WAIT_SECONDS how long to wait for a terminal state (default 180)
#   DEPLOY_POLL_SECONDS poll interval (default 5)
#
# Signature: X-Deploy-Signature = hex(HMAC-SHA256(secret, "<timestamp>.<body>")).
# Exit codes: 0 deployed (or already current), 1 deploy failed/rolled back, 2 usage/transport error.
set -euo pipefail

usage() { echo "usage: $0 <service> <image> <digest> [sha]" >&2; exit 2; }
[ $# -ge 3 ] || usage
service=$1 image=$2 digest=$3 sha=${4:-}
: "${DEPLOY_HOOK_URL:?DEPLOY_HOOK_URL is not set}"
: "${DEPLOY_HOOK_SECRET:?DEPLOY_HOOK_SECRET is not set}"
wait_s=${DEPLOY_WAIT_SECONDS:-180}
poll_s=${DEPLOY_POLL_SECONDS:-5}
base=${DEPLOY_HOOK_URL%/}

case "$digest" in sha256:*) ;; *) echo "::error::digest must look like sha256:..." >&2; exit 2 ;; esac

ua="couple-deploy/1 (+github-actions)"
curl_common=(--silent --show-error --max-time 20 --retry 3 --retry-delay 3 --retry-all-errors -A "$ua")

send_hook() {
  # A fresh timestamp/signature per attempt (the updater rejects replays of the same signature).
  local ts body sig code
  ts=$(date +%s)
  body=$(jq -cn --arg s "$service" --arg i "$image" --arg d "$digest" --arg h "$sha" --argjson t "$ts" \
    '{service:$s,image:$i,digest:$d,sha:$h,ts:$t}')
  sig=$(printf '%s.%s' "$ts" "$body" | openssl dgst -sha256 -hmac "$DEPLOY_HOOK_SECRET" -r | cut -d' ' -f1)
  code=$(curl --silent --show-error --max-time 20 -A "$ua" -o /tmp/deploy-hook-resp.json -w '%{http_code}' \
    -X POST "$base/_deploy/hook" \
    -H 'Content-Type: application/json' \
    -H "X-Deploy-Timestamp: $ts" \
    -H "X-Deploy-Signature: $sig" \
    --data-binary "$body") || code=000
  echo "$code"
}

code=000
for attempt in 1 2 3; do
  code=$(send_hook)
  case "$code" in
    202) break ;;
    000|408|429|5??) echo "hook attempt $attempt: HTTP $code, retrying" >&2; sleep $((attempt * 5)) ;;
    *) break ;;
  esac
done
if [ "$code" != 202 ]; then
  echo "::error::deploy hook rejected: HTTP $code $(cat /tmp/deploy-hook-resp.json 2>/dev/null || true)" >&2
  exit 2
fi
echo "hook accepted: $service $digest"

deadline=$(( $(date +%s) + wait_s ))
state=unknown
while [ "$(date +%s)" -lt "$deadline" ]; do
  sleep "$poll_s"
  if ! status=$(curl "${curl_common[@]}" "$base/_deploy/status"); then
    echo "status poll failed, retrying" >&2
    continue
  fi
  state=$(printf '%s' "$status" | jq -r --arg s "$service" --arg d "$digest" \
    '.deploys[$s] // {} | if .digest == $d then (.state // "unknown") else "pending" end' 2>/dev/null || echo unknown)
  echo "deploy state: $state"
  case "$state" in
    success|noop) echo "deployed $service @ $digest"; exit 0 ;;
    rolled_back|failed|rollback_failed)
      err=$(printf '%s' "$status" | jq -r --arg s "$service" '.deploys[$s].error // ""')
      echo "::error::deploy of $service failed: $state ($err)" >&2
      exit 1 ;;
  esac
done
echo "::error::timed out after ${wait_s}s waiting for deploy of $service (last state: $state)" >&2
exit 1
