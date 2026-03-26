#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

SERVER_URL="${MINDOS_SERVER:-http://localhost:8080}"
USER_ID="${MINDOS_USER:-local-user}"

if [[ "${1:-}" == "--help" ]]; then
  cat <<'USAGE'
Usage:
  ./solo-cli.sh
  ./solo-cli.sh --theme cyber
  ./solo-cli.sh chat --message "echo hello"
  ./solo-cli.sh --show-routing-details

Environment overrides:
  MINDOS_SERVER=http://localhost:8080
  MINDOS_USER=local-user

Notes:
  - This script only presets --server/--user defaults.
  - Existing mindos-cli command semantics stay unchanged.
USAGE
  exit 0
fi

EXTRA_ARGS="$*"
CLI_ARGS="--server ${SERVER_URL} --user ${USER_ID}"
if [[ -n "${EXTRA_ARGS}" ]]; then
  CLI_ARGS+=" ${EXTRA_ARGS}"
fi

./mvnw -q -pl mindos-cli -am exec:java \
  -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication \
  -Dexec.args="${CLI_ARGS}"

