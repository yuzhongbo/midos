#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$ROOT_DIR/scripts/unix/lib/mindos-env.sh"

DIST_SECRETS_FILE="$ROOT_DIR/dist/mindos-windows-server/mindos-secrets.properties"
LOCAL_OVERRIDE_FILE="${MINDOS_LOCAL_SECRETS_FILE:-$ROOT_DIR/mindos-secrets.local.properties}"
STRICT_MODE=false
DRY_RUN=false

FORWARD_ARGS=()
for arg in "$@"; do
  if [[ "$arg" == "--strict" ]]; then
    STRICT_MODE=true
    continue
  fi
  if [[ "$arg" == "--dry-run" ]]; then
    DRY_RUN=true
    continue
  fi
  FORWARD_ARGS+=("$arg")
done

echo "[INFO] Loading dist secrets: $DIST_SECRETS_FILE"
mindos_load_properties_file "$DIST_SECRETS_FILE"

if [[ -f "$LOCAL_OVERRIDE_FILE" ]]; then
  echo "[INFO] Loading local overrides: $LOCAL_OVERRIDE_FILE"
  mindos_load_properties_file "$LOCAL_OVERRIDE_FILE"
else
  echo "[INFO] Local override file not found (optional): $LOCAL_OVERRIDE_FILE"
fi

mindos_validate_runtime_maps
mindos_print_effective_summary
mindos_print_drift_warnings
mindos_print_or_fail_placeholder_warnings "$STRICT_MODE"

if [[ "$DRY_RUN" == "true" ]]; then
  echo "[INFO] Dry-run completed. No process started."
  exit 0
fi

PROFILE="${MINDOS_SPRING_PROFILE:-solo}"
cd "$ROOT_DIR"
exec ./mvnw -pl assistant-api -am spring-boot:run \
  -Dspring-boot.run.profiles="$PROFILE" \
  -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8 \
  "${FORWARD_ARGS[@]}"