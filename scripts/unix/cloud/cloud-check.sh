#!/usr/bin/env bash
set -euo pipefail

# MindOS 云服务器巡检脚本：Java/端口/目录/软链/进程状态。

usage() {
  cat <<'USAGE'
Usage:
  # 推荐：SSH key（默认）
  CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/cloud-check.sh

  # 临时兜底：密码（不推荐长期使用）
  CLOUD_HOST=1.2.3.4 CLOUD_USER=root CLOUD_PASS='***' ./scripts/unix/cloud/cloud-check.sh

Optional env:
  CLOUD_PORT=22
  SSH_KEY_PATH=~/.ssh/id_ed25519
  REMOTE_BASE_DIR=/home/<user>/mindos
  APP_PORT=8080

Exit code:
  0 -> no FAIL
  1 -> has FAIL
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
if [[ -z "${REMOTE_BASE_DIR:-}" ]]; then
  if [[ "$CLOUD_USER" == "root" ]]; then
    REMOTE_BASE_DIR="/root/mindos"
  else
    REMOTE_BASE_DIR="/home/${CLOUD_USER:-mindos}/mindos"
  fi
else
  REMOTE_BASE_DIR="$REMOTE_BASE_DIR"
fi
APP_PORT="${APP_PORT:-8080}"

if [[ -z "$CLOUD_HOST" || -z "$CLOUD_USER" ]]; then
  echo "[ERROR] CLOUD_HOST 和 CLOUD_USER 必填"
  usage
  exit 1
fi

if [[ -n "$CLOUD_PASS" ]] && ! command -v sshpass >/dev/null 2>&1; then
  echo "[ERROR] CLOUD_PASS 已设置，但未检测到 sshpass。"
  echo "        macOS: brew install hudochenkov/sshpass/sshpass"
  echo "        Ubuntu/Debian: sudo apt-get install -y sshpass"
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

echo "[INFO] Running cloud checks on $CLOUD_USER@$CLOUD_HOST ..."
read -r -d '' REMOTE_CMD <<EOF || true
set -euo pipefail
BASE_DIR='$REMOTE_BASE_DIR'
APP_PORT='$APP_PORT'
FAIL=0
WARN=0
PASS=0
TOTAL=0

report_pass() {
  TOTAL=$((TOTAL + 1))
  PASS=$((PASS + 1))
  echo "[PASS] $1"
}

report_warn() {
  TOTAL=$((TOTAL + 1))
  WARN=$((WARN + 1))
  echo "[WARN] $1"
}

report_fail() {
  TOTAL=$((TOTAL + 1))
  FAIL=$((FAIL + 1))
  echo "[FAIL] $1"
}

if command -v java >/dev/null 2>&1; then
  JVER="$(java -version 2>&1 | head -n 1)"
  if java -version 2>&1 | grep -q '"17\.'; then
    report_pass "java.version $JVER"
  else
    report_warn "java.version.expected17 actual=$JVER"
  fi
else
  report_fail "java.missing"
fi

for d in "\$BASE_DIR" "\$BASE_DIR/releases" "\$BASE_DIR/logs" "\$BASE_DIR/run"; do
  if [[ -d "\$d" ]]; then
    report_pass "dir.exists \$d"
  else
    report_fail "dir.missing \$d"
  fi
done

if [[ -L "\$BASE_DIR/current" ]]; then
  C_TARGET="$(readlink "\$BASE_DIR/current" || true)"
  if [[ -n "\$C_TARGET" && -f "\$BASE_DIR/current/assistant-api.jar" ]]; then
    report_pass "link.current \$C_TARGET"
  else
    report_fail "link.current.invalid"
  fi
else
  report_warn "link.current.missing"
fi

if [[ -L "\$BASE_DIR/previous" ]]; then
  P_TARGET="$(readlink "\$BASE_DIR/previous" || true)"
  if [[ -n "\$P_TARGET" && -f "\$P_TARGET/assistant-api.jar" ]]; then
    report_pass "link.previous \$P_TARGET"
  else
    report_warn "link.previous.invalid"
  fi
else
  report_warn "link.previous.missing"
fi

if command -v ss >/dev/null 2>&1; then
  if ss -ltn "( sport = :\$APP_PORT )" | tail -n +2 | grep -q .; then
    report_pass "port.listen \$APP_PORT"
  else
    report_warn "port.not-listening \$APP_PORT"
  fi
elif command -v lsof >/dev/null 2>&1; then
  if lsof -iTCP:"\$APP_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    report_pass "port.listen \$APP_PORT"
  else
    report_warn "port.not-listening \$APP_PORT"
  fi
else
  report_warn "port.check.skipped ss/lsof-not-found"
fi

if [[ -f "\$BASE_DIR/run/assistant-api.pid" ]]; then
  PID="$(cat "\$BASE_DIR/run/assistant-api.pid" || true)"
  if [[ -n "\$PID" ]] && kill -0 "\$PID" >/dev/null 2>&1; then
    report_pass "pid.running \$PID"
  else
    report_fail "pid.stale-file"
  fi
else
  report_warn "pid.file.missing"
fi

echo "SUMMARY total=\$TOTAL pass=\$PASS warn=\$WARN fail=\$FAIL"
if [[ "\$FAIL" -gt 0 ]]; then
  exit 1
fi
EOF

run_ssh "$REMOTE_CMD"

echo "[DONE] cloud-check finished"

