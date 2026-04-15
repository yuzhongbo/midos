#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$ROOT_DIR/scripts/unix/lib/mindos-env.sh"

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/unix/local/run.sh [--mode=local|release] [--check] [--dry-run] [--strict|--no-strict] [-- <mvn args>]

Examples:
  ./scripts/unix/local/run.sh
  ./scripts/unix/local/run.sh --check
  ./scripts/unix/local/run.sh --mode=release --dry-run
USAGE
}

MODE="local"
CHECK_ONLY=false
DRY_RUN=false
STRICT_MODE=""
FORWARD_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode=local|--local)
      MODE="local"
      ;;
    --mode=release|--release)
      MODE="release"
      ;;
    --check)
      CHECK_ONLY=true
      ;;
    --dry-run)
      DRY_RUN=true
      ;;
    --strict)
      STRICT_MODE="true"
      ;;
    --no-strict)
      STRICT_MODE="false"
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    --)
      shift
      FORWARD_ARGS+=("$@")
      break
      ;;
    *)
      FORWARD_ARGS+=("$1")
      ;;
  esac
  shift
done

if [[ -z "$STRICT_MODE" ]]; then
  if [[ "$MODE" == "release" ]]; then
    STRICT_MODE="true"
  else
    STRICT_MODE="false"
  fi
fi

DIST_SECRETS_FILE="$ROOT_DIR/dist/mindos-windows-server/mindos-secrets.properties"
DEFAULT_LOCAL_OVERRIDE_FILE="$ROOT_DIR/config/secrets/mindos-secrets.local.properties"
DEFAULT_RELEASE_OVERRIDE_FILE="$ROOT_DIR/config/secrets/mindos-secrets.release.properties"
LEGACY_LOCAL_OVERRIDE_FILE="$ROOT_DIR/mindos-secrets.local.properties"
LEGACY_RELEASE_OVERRIDE_FILE="$ROOT_DIR/mindos-secrets.release.properties"
RAW_LOCAL_OVERRIDE_FILE="${MINDOS_LOCAL_SECRETS_FILE:-}"
RAW_RELEASE_OVERRIDE_FILE="${MINDOS_RELEASE_SECRETS_FILE:-}"

LOCAL_OVERRIDE_FILE="$DEFAULT_LOCAL_OVERRIDE_FILE"
if [[ -n "$RAW_LOCAL_OVERRIDE_FILE" ]]; then
  LOCAL_OVERRIDE_FILE="$RAW_LOCAL_OVERRIDE_FILE"
elif [[ ! -f "$LOCAL_OVERRIDE_FILE" && -f "$LEGACY_LOCAL_OVERRIDE_FILE" ]]; then
  LOCAL_OVERRIDE_FILE="$LEGACY_LOCAL_OVERRIDE_FILE"
fi

RELEASE_OVERRIDE_FILE="$DEFAULT_RELEASE_OVERRIDE_FILE"
if [[ -n "$RAW_RELEASE_OVERRIDE_FILE" ]]; then
  RELEASE_OVERRIDE_FILE="$RAW_RELEASE_OVERRIDE_FILE"
elif [[ ! -f "$RELEASE_OVERRIDE_FILE" && -f "$LEGACY_RELEASE_OVERRIDE_FILE" ]]; then
  RELEASE_OVERRIDE_FILE="$LEGACY_RELEASE_OVERRIDE_FILE"
fi

OVERRIDE_FILE="$LOCAL_OVERRIDE_FILE"
OVERRIDE_LABEL="local overrides"
if [[ "$MODE" == "release" ]]; then
  OVERRIDE_FILE="$RELEASE_OVERRIDE_FILE"
  OVERRIDE_LABEL="release overrides"
fi

echo "[INFO] Runtime mode=$MODE strict=$STRICT_MODE"
echo "[INFO] Loading dist secrets: $DIST_SECRETS_FILE"
mindos_load_properties_file "$DIST_SECRETS_FILE"

if [[ -f "$OVERRIDE_FILE" ]]; then
  echo "[INFO] Loading $OVERRIDE_LABEL: $OVERRIDE_FILE"
  mindos_load_properties_file "$OVERRIDE_FILE"
else
  echo "[INFO] Optional override file not found: $OVERRIDE_FILE"
fi

mindos_apply_model_preset
mindos_validate_runtime_maps
mindos_print_effective_summary
mindos_print_drift_warnings
mindos_print_or_fail_placeholder_warnings "$STRICT_MODE"

if [[ "$CHECK_ONLY" == "true" ]]; then
  echo "[INFO] Preflight completed. No process started."
  exit 0
fi

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
