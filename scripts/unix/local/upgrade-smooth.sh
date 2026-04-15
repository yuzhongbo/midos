#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$ROOT_DIR/data/memory-backups"
MEMORY_DIR="$ROOT_DIR/data/memory-sync"
source "$ROOT_DIR/scripts/unix/lib/mindos-env.sh"

DIST_SECRETS_FILE="$ROOT_DIR/dist/mindos-windows-server/mindos-secrets.properties"
DEFAULT_LOCAL_OVERRIDE_FILE="$ROOT_DIR/config/secrets/mindos-secrets.local.properties"
LEGACY_LOCAL_OVERRIDE_FILE="$ROOT_DIR/mindos-secrets.local.properties"
RAW_LOCAL_OVERRIDE_FILE="${MINDOS_LOCAL_SECRETS_FILE:-}"
LOCAL_OVERRIDE_FILE="$DEFAULT_LOCAL_OVERRIDE_FILE"
if [[ -n "$RAW_LOCAL_OVERRIDE_FILE" ]]; then
  LOCAL_OVERRIDE_FILE="$RAW_LOCAL_OVERRIDE_FILE"
elif [[ ! -f "$LOCAL_OVERRIDE_FILE" && -f "$LEGACY_LOCAL_OVERRIDE_FILE" ]]; then
  LOCAL_OVERRIDE_FILE="$LEGACY_LOCAL_OVERRIDE_FILE"
fi
API_BASE_URL="${MINDOS_UPGRADE_API_BASE_URL:-${MINDOS_SERVER_BASE_URL:-http://localhost:${MINDOS_SERVER_PORT:-8080}}}"
ADMIN_TOKEN_HEADER_NAME="${MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN_HEADER:-X-MindOS-Admin-Token}"
ADMIN_TOKEN_VALUE="${MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN:-}"
DRAIN_TIMEOUT_MS_DEFAULT=30000
POLL_INTERVAL_SECONDS=1
DRAIN_TIMEOUT_MS="$DRAIN_TIMEOUT_MS_DEFAULT"
VERIFY_AFTER_RESTART=true

usage() {
  cat <<EOF
Usage: $0 [--dry-run]

Smooth upgrade helper (local single-host):
- Creates a timestamped backup of the file-backed memory directory
- Drains the running API so it stops accepting new requests
- Waits for readiness to turn false, then optionally prompts for a manual restart and verifies ready=true
- Keeps the final step as a manual restart prompt so the caller can launch the new version safely

Options:
  --dry-run   only print actions, do not modify files or start processes
  --base-url  Override API base URL (default: ${API_BASE_URL})
  --timeout-ms  Drain/readiness wait timeout in milliseconds (default: ${DRAIN_TIMEOUT_MS_DEFAULT})
  --skip-post-restart-check  stop after drain and pre-restart readiness=false confirmation
EOF
}

DRY_RUN=false
FORWARD_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
    DRY_RUN=true
      ;;
    --base-url)
      shift
      API_BASE_URL="${1:-$API_BASE_URL}"
      ;;
    --timeout-ms)
      shift
      DRAIN_TIMEOUT_MS="${1:-$DRAIN_TIMEOUT_MS}"
      ;;
    --skip-post-restart-check)
      VERIFY_AFTER_RESTART=false
      ;;
    *)
      FORWARD_ARGS+=("$1")
      ;;
  esac
  shift
done

if [[ -f "$DIST_SECRETS_FILE" ]]; then
  echo "[INFO] Loading dist secrets: $DIST_SECRETS_FILE"
  mindos_load_properties_file "$DIST_SECRETS_FILE"
else
  echo "[WARN] Dist secrets file not found: $DIST_SECRETS_FILE"
fi

if [[ -f "$LOCAL_OVERRIDE_FILE" ]]; then
  echo "[INFO] Loading local overrides: $LOCAL_OVERRIDE_FILE"
  mindos_load_properties_file "$LOCAL_OVERRIDE_FILE"
fi

if [[ -n "${MINDOS_UPGRADE_API_BASE_URL:-}" ]]; then
  API_BASE_URL="$MINDOS_UPGRADE_API_BASE_URL"
fi
if [[ -n "${MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN_HEADER:-}" ]]; then
  ADMIN_TOKEN_HEADER_NAME="$MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN_HEADER"
fi
if [[ -n "${MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN:-}" ]]; then
  ADMIN_TOKEN_VALUE="$MINDOS_SECURITY_RISKY_OPS_ADMIN_TOKEN"
fi

json_field() {
  local payload="$1"
  local field="$2"
  python3 - "$payload" "$field" <<'PY'
import json
import sys

payload = sys.argv[1]
field = sys.argv[2]
try:
    data = json.loads(payload)
except Exception:
    print("")
    raise SystemExit(0)
value = data.get(field, "")
if isinstance(value, bool):
    print("true" if value else "false")
else:
    print(value)
PY
}

http_get_json() {
  local url="$1"
  local header_args=()
  if [[ -n "$ADMIN_TOKEN_VALUE" ]] && ! mindos_is_placeholder "$ADMIN_TOKEN_VALUE"; then
    header_args+=("-H" "$ADMIN_TOKEN_HEADER_NAME: $ADMIN_TOKEN_VALUE")
  fi
  curl -fsS "${header_args[@]}" "$url"
}

http_post_json() {
  local url="$1"
  local header_args=("-H" "Content-Type: application/json")
  if [[ -n "$ADMIN_TOKEN_VALUE" ]] && ! mindos_is_placeholder "$ADMIN_TOKEN_VALUE"; then
    header_args+=("-H" "$ADMIN_TOKEN_HEADER_NAME: $ADMIN_TOKEN_VALUE")
  fi
  curl -fsS -X POST "${header_args[@]}" "$url"
}

wait_for_readiness_false() {
  local timeout_seconds=$(( (DRAIN_TIMEOUT_MS + 999) / 1000 ))
  local elapsed=0
  while (( elapsed < timeout_seconds )); do
    local body ready
    body="$(http_get_json "$API_BASE_URL/health/readiness" || true)"
    ready="$(json_field "$body" "ready" || true)"
    if [[ "$ready" == "false" ]]; then
      return 0
    fi
    sleep "$POLL_INTERVAL_SECONDS"
    elapsed=$((elapsed + POLL_INTERVAL_SECONDS))
  done
  return 1
}

wait_for_readiness_true() {
  local timeout_seconds=$(( (DRAIN_TIMEOUT_MS + 999) / 1000 ))
  local elapsed=0
  while (( elapsed < timeout_seconds )); do
    local body ready inflight active
    body="$(http_get_json "$API_BASE_URL/health/readiness" || true)"
    ready="$(json_field "$body" "ready" || true)"
    inflight="$(json_field "$body" "inflight" || true)"
    active="$(json_field "$body" "activeDispatches" || true)"
    echo "[INFO] readiness=$ready inflight=$inflight activeDispatches=$active"
    if [[ "$ready" == "true" ]]; then
      return 0
    fi
    sleep "$POLL_INTERVAL_SECONDS"
    elapsed=$((elapsed + POLL_INTERVAL_SECONDS))
  done
  return 1
}

echo "[INFO] ROOT_DIR=$ROOT_DIR"
echo "[INFO] MEMORY_DIR=$MEMORY_DIR"
echo "[INFO] API_BASE_URL=$API_BASE_URL"
echo "[INFO] DRAIN_TIMEOUT_MS=$DRAIN_TIMEOUT_MS"

if [[ ! -d "$MEMORY_DIR" ]]; then
  echo "[WARN] Memory directory does not exist: $MEMORY_DIR"
  echo "[INFO] Nothing to backup. You may still proceed to start the new version."
  exit 0
fi

BACKUP_FILE="$BACKUP_DIR/memory-backup-$TIMESTAMP.tar.gz"

echo "[INFO] Creating backup: $BACKUP_FILE"
if [[ "$DRY_RUN" == "true" ]]; then
  echo "DRY-RUN: tar -czf $BACKUP_FILE -C $(dirname "$MEMORY_DIR") $(basename "$MEMORY_DIR")"
else
  mkdir -p "$BACKUP_DIR"
  tar -czf "$BACKUP_FILE" -C "$(dirname "$MEMORY_DIR")" "$(basename "$MEMORY_DIR")"
  echo "[INFO] Backup created"
fi

echo "[INFO] Verifying backup size"
if [[ "$DRY_RUN" == "true" ]]; then
  echo "DRY-RUN: ls -lh $BACKUP_FILE"
else
  ls -lh "$BACKUP_FILE" || true
fi

if [[ "$DRY_RUN" == "true" ]]; then
  echo "[INFO] DRY-RUN: would POST $API_BASE_URL/admin/drain?timeoutMs=$DRAIN_TIMEOUT_MS"
  echo "[INFO] DRY-RUN: would poll $API_BASE_URL/health/readiness until ready=false then ready=true"
  echo "DRY-RUN complete. No further actions taken."
  exit 0
fi

echo "[INFO] Sending drain request to $API_BASE_URL/admin/drain?timeoutMs=$DRAIN_TIMEOUT_MS"
if ! drain_response="$(http_post_json "$API_BASE_URL/admin/drain?timeoutMs=$DRAIN_TIMEOUT_MS")"; then
  echo "[ERROR] Drain request failed. Backup remains available at: $BACKUP_FILE"
  exit 1
fi
echo "[INFO] Drain response: $drain_response"

echo "[INFO] Waiting for readiness to turn false..."
if ! wait_for_readiness_false; then
  echo "[WARN] Readiness did not flip to false within timeout, continue polling for final state."
fi

if [[ "$VERIFY_AFTER_RESTART" == "true" ]]; then
  echo "[INFO] Waiting for readiness to turn true after manual restart..."
  echo "[INFO] Restart or replace the running process with the new version now."
  read -r -p "Press Enter after the new process is up to verify readiness... " _
  if ! wait_for_readiness_true; then
    echo "[ERROR] Service did not become ready within timeout. Check logs and $BACKUP_FILE before retrying."
    exit 1
  fi
  echo "[INFO] Post-restart readiness verified."
else
  echo "[INFO] Skipping post-restart readiness check per flag."
fi

echo "[INFO] Smooth upgrade prep complete. Backup: $BACKUP_FILE"
cat <<EOF
Next step:
  1) Restart or replace the running process with the new version.
  2) Re-run the health check if you want to confirm the node remains ready.
EOF
