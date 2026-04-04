#!/usr/bin/env bash
set -euo pipefail

# MindOS 云服务器初始化脚本：创建目录、校正权限、预检查 Java/端口/PID。

usage() {
  cat <<'USAGE'
Usage:
  # 推荐：SSH key（默认）
  CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/cloud-init.sh

  # 临时兜底：密码（不推荐长期使用）
  CLOUD_HOST=1.2.3.4 CLOUD_USER=root CLOUD_PASS='***' ./scripts/unix/cloud/cloud-init.sh

Optional env:
  CLOUD_PORT=22
  SSH_KEY_PATH=~/.ssh/id_ed25519
  REMOTE_BASE_DIR=/home/<user>/mindos
  APP_PORT=8080

Notes:
  - 该脚本不会启动应用，只做环境初始化与基础巡检。
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

echo "[INFO] Initializing remote directories and baseline checks..."
read -r -d '' REMOTE_CMD <<EOF || true
set -euo pipefail
BASE_DIR='$REMOTE_BASE_DIR'
APP_PORT='$APP_PORT'

mkdir -p "\$BASE_DIR/releases" "\$BASE_DIR/logs" "\$BASE_DIR/run"
chmod 755 "\$BASE_DIR" "\$BASE_DIR/releases" "\$BASE_DIR/logs" "\$BASE_DIR/run"

echo "[PASS] dirs.ready base=\$BASE_DIR"

if command -v java >/dev/null 2>&1; then
  JAVA_VER="$(java -version 2>&1 | head -n 1)"
  if java -version 2>&1 | grep -q '"17\.'; then
    echo "[PASS] java.version \$JAVA_VER"
  else
    echo "[WARN] java.version.expected17 actual=\$JAVA_VER"
  fi
else
  echo "[FAIL] java.missing install Java 17 first"
fi

if command -v ss >/dev/null 2>&1; then
  if ss -ltn | awk '{print \$4}' | grep -qE '(^|:)\$APP_PORT\$'; then
    echo "[WARN] port.inuse \$APP_PORT"
  else
    echo "[PASS] port.free \$APP_PORT"
  fi
elif command -v lsof >/dev/null 2>&1; then
  if lsof -iTCP:"\$APP_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "[WARN] port.inuse \$APP_PORT"
  else
    echo "[PASS] port.free \$APP_PORT"
  fi
else
  echo "[WARN] port.check.skipped ss/lsof not found"
fi

if [[ -f "\$BASE_DIR/run/assistant-api.pid" ]]; then
  PID="$(cat "\$BASE_DIR/run/assistant-api.pid" || true)"
  if [[ -n "\$PID" ]] && kill -0 "\$PID" >/dev/null 2>&1; then
    echo "[WARN] pid.running \$PID"
  else
    rm -f "\$BASE_DIR/run/assistant-api.pid"
    echo "[PASS] pid.cleaned stale-file-removed"
  fi
else
  echo "[PASS] pid.clean no-pid-file"
fi
EOF

run_ssh "$REMOTE_CMD"
echo "[DONE] cloud-init finished for $CLOUD_USER@$CLOUD_HOST"

