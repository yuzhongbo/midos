#!/usr/bin/env bash
set -euo pipefail

# MindOS assistant-api 简易发布脚本（默认 SSH key，密码仅临时兜底）。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

usage() {
  cat <<'USAGE'
Usage:
  # 推荐：SSH key（默认）
  CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./deploy-cloud.sh

  # 临时兜底：密码（不推荐长期使用）
  CLOUD_HOST=1.2.3.4 CLOUD_USER=root CLOUD_PASS='***' ./deploy-cloud.sh

Optional env:
  CLOUD_PORT=22
  SSH_KEY_PATH=~/.ssh/id_ed25519
  REMOTE_BASE_DIR=/home/<user>/mindos
  APP_PORT=8080
  BUILD=1
  SKIP_TESTS=1
  JAVA_OPTS='-Xms256m -Xmx512m'

Notes:
  - 默认使用 SSH key（推荐）；首次可先运行 ./init-authorized-keys.sh 初始化。
  - 若设置 CLOUD_PASS，则需要本机安装 sshpass（仅临时兜底，不建议长期使用）。
  - 默认会构建 assistant-api 并上传发布。
  - 远端保留 releases 历史版本，并维护 current/previous 软链，支持手动回滚。
USAGE
}

if [[ "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

CLOUD_HOST="${CLOUD_HOST:-}"
CLOUD_USER="${CLOUD_USER:-}"
CLOUD_PASS="${CLOUD_PASS:-}"
CLOUD_PORT="${CLOUD_PORT:-22}"
SSH_KEY_PATH="${SSH_KEY_PATH:-$HOME/.ssh/id_ed25519}"
REMOTE_BASE_DIR="${REMOTE_BASE_DIR:-/home/${CLOUD_USER:-mindos}/mindos}"
APP_PORT="${APP_PORT:-8080}"
BUILD="${BUILD:-1}"
SKIP_TESTS="${SKIP_TESTS:-1}"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}"

if [[ -z "$CLOUD_HOST" || -z "$CLOUD_USER" ]]; then
  echo "[ERROR] CLOUD_HOST 和 CLOUD_USER 必填"
  usage
  exit 1
fi

if [[ "$BUILD" == "1" ]]; then
  echo "[INFO] Building assistant-api..."
  if [[ "$SKIP_TESTS" == "1" ]]; then
    ./mvnw -q -pl assistant-api -am clean package -DskipTests
  else
    ./mvnw -q -pl assistant-api -am clean package
  fi
fi

JAR_PATH="$(ls -t assistant-api/target/assistant-api-*.jar | grep -v '\.original$' | head -n 1)"
if [[ -z "$JAR_PATH" || ! -f "$JAR_PATH" ]]; then
  echo "[ERROR] Cannot find built jar under assistant-api/target"
  exit 1
fi

RELEASE_ID="$(date +%Y%m%d%H%M%S)"
REMOTE_RELEASE_DIR="$REMOTE_BASE_DIR/releases/$RELEASE_ID"
REMOTE_JAR_NAME="assistant-api.jar"

if [[ -n "$CLOUD_PASS" ]] && ! command -v sshpass >/dev/null 2>&1; then
  echo "[ERROR] CLOUD_PASS 已设置，但未检测到 sshpass。"
  echo "        macOS 可先执行: brew install hudochenkov/sshpass/sshpass"
  exit 1
fi

SSH_OPTS=(-p "$CLOUD_PORT" -o StrictHostKeyChecking=accept-new)
SCP_OPTS=(-P "$CLOUD_PORT" -o StrictHostKeyChecking=accept-new)
if [[ -f "$SSH_KEY_PATH" ]]; then
  SSH_OPTS+=(-i "$SSH_KEY_PATH")
  SCP_OPTS+=(-i "$SSH_KEY_PATH")
fi

PASS_FILE=""
cleanup() {
  if [[ -n "$PASS_FILE" && -f "$PASS_FILE" ]]; then
    rm -f "$PASS_FILE"
  fi
}
trap cleanup EXIT

if [[ -n "$CLOUD_PASS" ]]; then
  PASS_FILE="$(mktemp)"
  chmod 600 "$PASS_FILE"
  printf '%s' "$CLOUD_PASS" > "$PASS_FILE"
else
  # 无密码模式下先探测 key 登录，提前给出可执行指引。
  if ! ssh "${SSH_OPTS[@]}" -o BatchMode=yes -o ConnectTimeout=5 "$CLOUD_USER@$CLOUD_HOST" "true" >/dev/null 2>&1; then
    echo "[ERROR] SSH key 登录失败，且未提供 CLOUD_PASS。"
    echo "        请先执行: CLOUD_HOST=$CLOUD_HOST CLOUD_USER=$CLOUD_USER ./init-authorized-keys.sh"
    exit 1
  fi
fi

run_ssh() {
  local cmd="$1"
  if [[ -n "$CLOUD_PASS" ]]; then
    sshpass -f "$PASS_FILE" ssh "${SSH_OPTS[@]}" "$CLOUD_USER@$CLOUD_HOST" "$cmd"
  else
    ssh "${SSH_OPTS[@]}" "$CLOUD_USER@$CLOUD_HOST" "$cmd"
  fi
}

run_scp() {
  local src="$1"
  local dst="$2"
  if [[ -n "$CLOUD_PASS" ]]; then
    sshpass -f "$PASS_FILE" scp "${SCP_OPTS[@]}" "$src" "$dst"
  else
    scp "${SCP_OPTS[@]}" "$src" "$dst"
  fi
}

echo "[INFO] Creating remote directories..."
run_ssh "mkdir -p '$REMOTE_RELEASE_DIR' '$REMOTE_BASE_DIR/logs' '$REMOTE_BASE_DIR/run'"

echo "[INFO] Uploading jar: $JAR_PATH"
run_scp "$JAR_PATH" "$CLOUD_USER@$CLOUD_HOST:$REMOTE_RELEASE_DIR/$REMOTE_JAR_NAME"

echo "[INFO] Activating release on remote host..."
read -r -d '' REMOTE_CMD <<EOF || true
set -euo pipefail
BASE_DIR='$REMOTE_BASE_DIR'
RELEASE_DIR='$REMOTE_RELEASE_DIR'
JAR_NAME='$REMOTE_JAR_NAME'
APP_PORT='$APP_PORT'
JAVA_OPTS='$JAVA_OPTS'

mkdir -p "\$BASE_DIR/releases" "\$BASE_DIR/logs" "\$BASE_DIR/run"

if [[ -L "\$BASE_DIR/current" ]]; then
  CURRENT_TARGET="$(readlink "\$BASE_DIR/current" || true)"
  if [[ -n "\$CURRENT_TARGET" ]]; then
    ln -sfn "\$CURRENT_TARGET" "\$BASE_DIR/previous"
  fi
fi

if [[ -f "\$BASE_DIR/run/assistant-api.pid" ]]; then
  OLD_PID="$(cat "\$BASE_DIR/run/assistant-api.pid" || true)"
  if [[ -n "\$OLD_PID" ]] && kill -0 "\$OLD_PID" >/dev/null 2>&1; then
    kill "\$OLD_PID" || true
    sleep 2
    kill -9 "\$OLD_PID" >/dev/null 2>&1 || true
  fi
fi

ln -sfn "\$RELEASE_DIR" "\$BASE_DIR/current"
nohup bash -c "cd '\$BASE_DIR/current' && exec java \$JAVA_OPTS -jar '\$BASE_DIR/current/\$JAR_NAME' --server.port=\$APP_PORT" \
  >"\$BASE_DIR/logs/assistant-api.out" 2>&1 &
NEW_PID=\$!
echo "\$NEW_PID" > "\$BASE_DIR/run/assistant-api.pid"
sleep 2

if ! kill -0 "\$NEW_PID" >/dev/null 2>&1; then
  echo "[ERROR] assistant-api failed to start, check \$BASE_DIR/logs/assistant-api.out"
  exit 1
fi

echo "[OK] deployed release=$RELEASE_DIR pid=\$NEW_PID"
EOF
run_ssh "$REMOTE_CMD"

echo "[DONE] Deploy success"
echo "       Host: $CLOUD_HOST"
echo "       Current: $REMOTE_BASE_DIR/current/$REMOTE_JAR_NAME"
echo "       Logs: $REMOTE_BASE_DIR/logs/assistant-api.out"
echo "       Health check: curl http://$CLOUD_HOST:$APP_PORT/chat -H 'Content-Type: application/json' -d '{\"userId\":\"u1\",\"message\":\"echo hi\"}'"

