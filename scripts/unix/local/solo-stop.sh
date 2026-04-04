#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT_DIR"

PORT="${MINDOS_PORT:-8080}"
PATTERN="${MINDOS_PROCESS_PATTERN:-MindOsApplication|assistant-api-0.1.0-SNAPSHOT|spring-boot:run}"
FORCE="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port)
      PORT="${2:-}"
      shift 2
      ;;
    --pattern)
      PATTERN="${2:-}"
      shift 2
      ;;
    --force)
      FORCE="true"
      shift
      ;;
    --help)
      cat <<'USAGE'
Usage:
  ./scripts/unix/local/solo-stop.sh
  ./scripts/unix/local/solo-stop.sh --port 8080
  ./scripts/unix/local/solo-stop.sh --pattern "MindOsApplication|assistant-api"
  ./scripts/unix/local/solo-stop.sh --force

Environment overrides:
  MINDOS_PORT=8080
  MINDOS_PROCESS_PATTERN="MindOsApplication|assistant-api-0.1.0-SNAPSHOT|spring-boot:run"

Behavior:
  1) Try stopping process(es) listening on the target port.
  2) Then try stopping process(es) matching the process-name pattern.
  3) Uses SIGTERM by default, SIGKILL when --force is set.
USAGE
      exit 0
      ;;
    *)
      echo "Unknown argument: $1"
      echo "Run ./scripts/unix/local/solo-stop.sh --help"
      exit 1
      ;;
  esac
done

if [[ -z "$PORT" ]]; then
  echo "[FAIL] Empty port value"
  exit 1
fi

SIGNAL="-TERM"
if [[ "$FORCE" == "true" ]]; then
  SIGNAL="-KILL"
fi

stopped_any="false"

port_pids="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' | xargs || true)"
if [[ -n "$port_pids" ]]; then
  echo "[INFO] Stopping PID(s) on port ${PORT}: ${port_pids}"
  kill "$SIGNAL" $port_pids || true
  stopped_any="true"
else
  echo "[INFO] No listening process found on port ${PORT}"
fi

pattern_pids="$(pgrep -f "$PATTERN" 2>/dev/null | tr '\n' ' ' | xargs || true)"
if [[ -n "$pattern_pids" ]]; then
  echo "[INFO] Stopping PID(s) matching pattern '${PATTERN}': ${pattern_pids}"
  kill "$SIGNAL" $pattern_pids || true
  stopped_any="true"
else
  echo "[INFO] No process matched pattern '${PATTERN}'"
fi

if [[ "$stopped_any" == "true" ]]; then
  echo "[PASS] Stop signal ${SIGNAL} sent"
else
  echo "[PASS] Nothing to stop"
fi
