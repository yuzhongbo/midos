#!/usr/bin/env bash
set -euo pipefail

# MindOS assistant-api 一键回滚脚本（从 previous 回切到 current）。

usage() {
  cat <<'USAGE'
Usage:
  # 推荐：SSH key（默认）
  CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/rollback-cloud.sh

  # 临时兜底：密码（不推荐长期使用）
  CLOUD_HOST=1.2.3.4 CLOUD_USER=root CLOUD_PASS='***' ./scripts/unix/cloud/rollback-cloud.sh

Optional env:
  CLOUD_PORT=22
  SSH_KEY_PATH=~/.ssh/id_ed25519
  REMOTE_BASE_DIR=/home/<user>/mindos
  APP_PORT=8080
  JAVA_OPTS='-Xms256m -Xmx512m -Dfile.encoding=UTF-8'

Notes:
  - 远端要求已存在 previous 软链（通常由 deploy-cloud.sh 自动维护）。
  - 回滚会先停止旧进程，再把 current 指向 previous 并启动服务。
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

ensure_utf8_java_opts() {
  local opts="$1"
  if [[ "$opts" != *"-Dfile.encoding="* ]]; then
    opts="${opts:+$opts }-Dfile.encoding=UTF-8"
  fi
  printf '%s' "$opts"
}

JAVA_OPTS="$(ensure_utf8_java_opts "${JAVA_OPTS:--Xms256m -Xmx512m}")"

if [[ -z "$CLOUD_HOST" || -z "$CLOUD_USER" ]]; then
  echo "[ERROR] CLOUD_HOST 和 CLOUD_USER 必填"
  usage
  exit 1
fi

if [[ -n "$CLOUD_PASS" ]] && ! command -v sshpass >/dev/null 2>&1; then
  echo "[ERROR] CLOUD_PASS 已设置，但未检测到 sshpass。"
  echo "        macOS 可先执行: brew install hudochenkov/sshpass/sshpass"
  exit 1
fi

SSH_OPTS=(-p "$CLOUD_PORT" -o StrictHostKeyChecking=accept-new)
if [[ -f "$SSH_KEY_PATH" ]]; then
  SSH_OPTS+=(-i "$SSH_KEY_PATH")
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
  if ! ssh "${SSH_OPTS[@]}" -o BatchMode=yes -o ConnectTimeout=5 "$CLOUD_USER@$CLOUD_HOST" "true" >/dev/null 2>&1; then
    echo "[ERROR] SSH key 登录失败，且未提供 CLOUD_PASS。"
    echo "        请先执行: CLOUD_HOST=$CLOUD_HOST CLOUD_USER=$CLOUD_USER ./scripts/unix/cloud/init-authorized-keys.sh"
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

echo "[INFO] Running rollback on remote host..."
read -r -d '' REMOTE_CMD <<EOF || true
set -euo pipefail
BASE_DIR='$REMOTE_BASE_DIR'
APP_PORT='$APP_PORT'
JAVA_OPTS='$JAVA_OPTS'

if [[ ! -L "\$BASE_DIR/previous" ]]; then
  echo "[ERROR] missing previous symlink: \$BASE_DIR/previous"
  exit 1
fi

TARGET="$(readlink "\$BASE_DIR/previous" || true)"
if [[ -z "\$TARGET" ]]; then
  echo "[ERROR] previous symlink is empty"
  exit 1
fi

if [[ ! -f "\$TARGET/assistant-api.jar" ]]; then
  echo "[ERROR] rollback target jar not found: \$TARGET/assistant-api.jar"
  exit 1
fi

mkdir -p "\$BASE_DIR/logs" "\$BASE_DIR/run"

if [[ -f "\$BASE_DIR/run/assistant-api.pid" ]]; then
  OLD_PID="$(cat "\$BASE_DIR/run/assistant-api.pid" || true)"
  if [[ -n "\$OLD_PID" ]] && kill -0 "\$OLD_PID" >/dev/null 2>&1; then
    kill "\$OLD_PID" || true
    sleep 2
    kill -9 "\$OLD_PID" >/dev/null 2>&1 || true
  fi
fi

CURRENT_TARGET="$(readlink "\$BASE_DIR/current" || true)"
if [[ -n "\$CURRENT_TARGET" ]]; then
  ln -sfn "\$CURRENT_TARGET" "\$BASE_DIR/failed"
fi

ln -sfn "\$TARGET" "\$BASE_DIR/current"
nohup bash -c "cd '\$BASE_DIR/current' && exec java \$JAVA_OPTS -jar '\$BASE_DIR/current/assistant-api.jar' --server.port=\$APP_PORT" \
  >"\$BASE_DIR/logs/assistant-api.out" 2>&1 &
NEW_PID=\$!
echo "\$NEW_PID" > "\$BASE_DIR/run/assistant-api.pid"
sleep 2

if ! kill -0 "\$NEW_PID" >/dev/null 2>&1; then
  echo "[ERROR] rollback start failed, check \$BASE_DIR/logs/assistant-api.out"
  exit 1
fi

echo "[OK] rolled back to \$TARGET pid=\$NEW_PID"
EOF
run_ssh "$REMOTE_CMD"

echo "[DONE] Rollback success"
echo "       Host: $CLOUD_HOST"
echo "       Current: $REMOTE_BASE_DIR/current/assistant-api.jar"
echo "       Logs: $REMOTE_BASE_DIR/logs/assistant-api.out"
echo "       Health check: curl http://$CLOUD_HOST:$APP_PORT/chat -H 'Content-Type: application/json' -d '{\"userId\":\"u1\",\"message\":\"echo hi\"}'"

