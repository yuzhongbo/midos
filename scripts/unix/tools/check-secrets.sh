#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$ROOT_DIR/scripts/unix/lib/mindos-env.sh"

MODE="local"
STRICT_MODE="true"

for arg in "$@"; do
  case "$arg" in
    --mode=local)
      MODE="local"
      ;;
    --mode=release)
      MODE="release"
      ;;
    --no-strict)
      STRICT_MODE="false"
      ;;
    *)
      echo "[ERROR] Unknown argument: $arg"
      echo "Usage: scripts/check-secrets.sh [--mode=local|--mode=release] [--no-strict]"
      exit 2
      ;;
  esac
done

DIST_SECRETS_FILE="$ROOT_DIR/dist/mindos-windows-server/mindos-secrets.properties"
LOCAL_OVERRIDE_FILE="${MINDOS_LOCAL_SECRETS_FILE:-$ROOT_DIR/mindos-secrets.local.properties}"
RELEASE_OVERRIDE_FILE="${MINDOS_RELEASE_SECRETS_FILE:-$ROOT_DIR/mindos-secrets.release.properties}"

echo "[INFO] Checking secrets with mode=$MODE strict=$STRICT_MODE"
echo "[INFO] Loading dist secrets: $DIST_SECRETS_FILE"
mindos_load_properties_file "$DIST_SECRETS_FILE"

if [[ "$MODE" == "local" ]]; then
  if [[ -f "$LOCAL_OVERRIDE_FILE" ]]; then
    echo "[INFO] Loading local overrides: $LOCAL_OVERRIDE_FILE"
    mindos_load_properties_file "$LOCAL_OVERRIDE_FILE"
  else
    echo "[INFO] Local override file not found (optional): $LOCAL_OVERRIDE_FILE"
  fi
else
  if [[ -f "$RELEASE_OVERRIDE_FILE" ]]; then
    echo "[INFO] Loading release overrides: $RELEASE_OVERRIDE_FILE"
    mindos_load_properties_file "$RELEASE_OVERRIDE_FILE"
  else
    echo "[INFO] Release override file not found (optional): $RELEASE_OVERRIDE_FILE"
  fi
fi

mindos_validate_runtime_maps
mindos_print_effective_summary
mindos_print_drift_warnings
mindos_print_or_fail_placeholder_warnings "$STRICT_MODE"

echo "[INFO] Secrets preflight check passed."