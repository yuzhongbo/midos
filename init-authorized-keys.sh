#!/usr/bin/env bash
set -euo pipefail

# 初始化远端 SSH authorized_keys，供 deploy-cloud.sh 后续走免密部署。

usage() {
  cat <<'USAGE'
Usage:
  CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./init-authorized-keys.sh

Optional env:
  CLOUD_PORT=22
  SSH_PUB_KEY_PATH=~/.ssh/id_ed25519.pub
  CLOUD_PASS='***'   # 临时兜底；推荐留空并按 ssh 交互输入

Notes:
  - 若本地未找到公钥，会自动生成 ed25519 密钥对。
  - 写入远端 ~/.ssh/authorized_keys 时会自动去重并修正权限(700/600)。
USAGE
}

if [[ "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

CLOUD_HOST="${CLOUD_HOST:-}"
CLOUD_USER="${CLOUD_USER:-}"
CLOUD_PORT="${CLOUD_PORT:-22}"
CLOUD_PASS="${CLOUD_PASS:-}"
SSH_PUB_KEY_PATH="${SSH_PUB_KEY_PATH:-$HOME/.ssh/id_ed25519.pub}"
SSH_KEY_PATH="${SSH_PUB_KEY_PATH%.pub}"

if [[ -z "$CLOUD_HOST" || -z "$CLOUD_USER" ]]; then
  echo "[ERROR] CLOUD_HOST 和 CLOUD_USER 必填"
  usage
  exit 1
fi

if [[ ! -f "$SSH_PUB_KEY_PATH" ]]; then
  echo "[INFO] Public key not found, generating key pair: $SSH_KEY_PATH"
  mkdir -p "$(dirname "$SSH_KEY_PATH")"
  chmod 700 "$(dirname "$SSH_KEY_PATH")"
  ssh-keygen -t ed25519 -f "$SSH_KEY_PATH" -N "" -C "mindos-deploy@$(hostname)"
fi

PUB_KEY_CONTENT="$(cat "$SSH_PUB_KEY_PATH")"
if [[ -z "$PUB_KEY_CONTENT" ]]; then
  echo "[ERROR] Empty public key: $SSH_PUB_KEY_PATH"
  exit 1
fi

SSH_OPTS=(-p "$CLOUD_PORT" -o StrictHostKeyChecking=accept-new)

PASS_FILE=""
cleanup() {
  if [[ -n "$PASS_FILE" && -f "$PASS_FILE" ]]; then
    rm -f "$PASS_FILE"
  fi
}
trap cleanup EXIT

run_ssh() {
  local cmd="$1"
  if [[ -n "$CLOUD_PASS" ]]; then
    sshpass -f "$PASS_FILE" ssh "${SSH_OPTS[@]}" "$CLOUD_USER@$CLOUD_HOST" "$cmd"
  else
    ssh "${SSH_OPTS[@]}" "$CLOUD_USER@$CLOUD_HOST" "$cmd"
  fi
}

if [[ -n "$CLOUD_PASS" ]]; then
  if ! command -v sshpass >/dev/null 2>&1; then
    echo "[ERROR] CLOUD_PASS 已设置，但未检测到 sshpass。"
    echo "        macOS 可先执行: brew install hudochenkov/sshpass/sshpass"
    exit 1
  fi
  PASS_FILE="$(mktemp)"
  chmod 600 "$PASS_FILE"
  printf '%s' "$CLOUD_PASS" > "$PASS_FILE"
fi

ESCAPED_KEY="$(printf '%s' "$PUB_KEY_CONTENT" | sed "s/'/'\\''/g")"

run_ssh "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && (grep -F '$ESCAPED_KEY' ~/.ssh/authorized_keys >/dev/null 2>&1 || echo '$ESCAPED_KEY' >> ~/.ssh/authorized_keys)"

echo "[OK] authorized_keys initialized for $CLOUD_USER@$CLOUD_HOST"
echo "     Next: CLOUD_HOST=$CLOUD_HOST CLOUD_USER=$CLOUD_USER ./deploy-cloud.sh"

