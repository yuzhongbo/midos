#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

SERVER_URL="${MINDOS_SERVER:-http://localhost:8080}"
USER_ID="${MINDOS_USER:-solo-smoke-user}"
TIMEOUT_SECONDS="${MINDOS_SMOKE_TIMEOUT_SECONDS:-8}"

if [[ "${1:-}" == "--help" ]]; then
  cat <<'USAGE'
Usage:
  ./solo-smoke.sh

Environment overrides:
  MINDOS_SERVER=http://localhost:8080
  MINDOS_USER=solo-smoke-user
  MINDOS_SMOKE_TIMEOUT_SECONDS=8

Checks:
  1) /chat echo route
  2) /api/metrics/llm availability
USAGE
  exit 0
fi

post_chat_payload="{\"userId\":\"${USER_ID}\",\"message\":\"echo smoke\"}"

chat_response="$(curl --silent --show-error --max-time "${TIMEOUT_SECONDS}" \
  -H 'Content-Type: application/json' \
  -d "${post_chat_payload}" \
  "${SERVER_URL}/chat")"

chat_channel="$(python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get("channel",""))' <<<"${chat_response}")"
if [[ "${chat_channel}" != "echo" ]]; then
  echo "[FAIL] /chat channel expected echo, got: ${chat_channel}"
  echo "response: ${chat_response}"
  exit 1
fi

echo "[OK] /chat echo route"

metrics_response="$(curl --silent --show-error --max-time "${TIMEOUT_SECONDS}" "${SERVER_URL}/api/metrics/llm")"
metrics_window="$(python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get("windowMinutes",""))' <<<"${metrics_response}")"
if [[ -z "${metrics_window}" ]]; then
  echo "[FAIL] /api/metrics/llm missing windowMinutes"
  echo "response: ${metrics_response}"
  exit 1
fi

echo "[OK] /api/metrics/llm reachable (windowMinutes=${metrics_window})"
echo "[PASS] solo smoke checks completed"

