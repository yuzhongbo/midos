#!/usr/bin/env bash
set -euo pipefail

# MindOS assistant-api 简易发布脚本（默认 SSH key，密码仅临时兜底）。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$ROOT_DIR"

usage() {
  cat <<'USAGE'
Usage:
  # 推荐：SSH key（默认）
  CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/deploy-cloud.sh

  # 临时兜底：密码（不推荐长期使用）
  CLOUD_HOST=1.2.3.4 CLOUD_USER=root CLOUD_PASS='***' ./scripts/unix/cloud/deploy-cloud.sh

Optional env:
  CLOUD_PORT=22
  SSH_KEY_PATH=~/.ssh/id_ed25519
  REMOTE_BASE_DIR=/home/<user>/mindos
  APP_PORT=8080
  BUILD=1
  SKIP_TESTS=1
  JAVA_OPTS='-Xms256m -Xmx512m -Dfile.encoding=UTF-8'

Notes:
  - 默认使用 SSH key（推荐）；首次可先运行 ./scripts/unix/cloud/init-authorized-keys.sh 初始化。
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

# 可选：将 release secrets 上传到远端主机。控制参数：
#   UPLOAD_SECRETS=1               => 尝试上传发布用的 secrets
#   MINDOS_RELEASE_SECRETS_FILE    => 本地的 release secrets 路径（默认：$ROOT_DIR/config/secrets/mindos-secrets.release.properties）
#   SECRETS_REMOTE_PATH            => 远端写入路径（默认：$REMOTE_BASE_DIR/config/secrets/mindos-secrets.release.properties）
#   UPLOAD_SECRETS_FORCE=1         => 强制覆盖远端文件（危险）
#   UPLOAD_SECRETS_MERGE=1         => 合并缺失/占位键到远端文件（默认，优先保留远端真实值）
# 行为说明：当远端已存在文件且启用合并时，会保留远端已经有且非占位的值；
# 否则本地值会替换占位或追加缺失键。上传后会尝试设置远端文件权限为 600。
UPLOAD_SECRETS="${UPLOAD_SECRETS:-0}"
DEFAULT_SECRETS_FILE="$ROOT_DIR/config/secrets/mindos-secrets.release.properties"
LEGACY_SECRETS_FILE="$ROOT_DIR/mindos-secrets.release.properties"
RAW_SECRETS_FILE="${MINDOS_RELEASE_SECRETS_FILE:-}"
if [[ -n "$RAW_SECRETS_FILE" ]]; then
  SECRETS_FILE="$RAW_SECRETS_FILE"
elif [[ -f "$DEFAULT_SECRETS_FILE" || ! -f "$LEGACY_SECRETS_FILE" ]]; then
  SECRETS_FILE="$DEFAULT_SECRETS_FILE"
else
  SECRETS_FILE="$LEGACY_SECRETS_FILE"
fi

DEFAULT_REMOTE_SECRETS_PATH="$REMOTE_BASE_DIR/config/secrets/mindos-secrets.release.properties"
LEGACY_REMOTE_SECRETS_PATH="$REMOTE_BASE_DIR/mindos-secrets.release.properties"
RAW_SECRETS_REMOTE_PATH="${SECRETS_REMOTE_PATH:-}"
if [[ -n "$RAW_SECRETS_REMOTE_PATH" ]]; then
  SECRETS_REMOTE_PATH="$RAW_SECRETS_REMOTE_PATH"
else
  SECRETS_REMOTE_PATH="$DEFAULT_REMOTE_SECRETS_PATH"
fi

SECRETS_REMOTE_DIR="$(dirname "$SECRETS_REMOTE_PATH")"
UPLOAD_SECRETS_FORCE="${UPLOAD_SECRETS_FORCE:-0}"
UPLOAD_SECRETS_MERGE="${UPLOAD_SECRETS_MERGE:-1}"

if [[ "$UPLOAD_SECRETS" == "1" ]]; then
  if [[ -z "$RAW_SECRETS_REMOTE_PATH" ]] \
    && run_ssh "[ -f '$LEGACY_REMOTE_SECRETS_PATH' ] && [ ! -f '$DEFAULT_REMOTE_SECRETS_PATH' ] && echo yes || echo no" | grep -q yes; then
    SECRETS_REMOTE_PATH="$LEGACY_REMOTE_SECRETS_PATH"
    SECRETS_REMOTE_DIR="$(dirname "$SECRETS_REMOTE_PATH")"
  fi
  echo "[INFO] Secrets upload requested. Local: $SECRETS_FILE  Remote: $SECRETS_REMOTE_PATH"
  run_ssh "mkdir -p '$SECRETS_REMOTE_DIR'"

  # 检查本地 secrets 文件是否存在，否则报错
  if [[ ! -f "$SECRETS_FILE" ]]; then
    echo "[ERROR] Secrets upload requested but local secrets file not found: $SECRETS_FILE"
    echo "        Create the file or set MINDOS_RELEASE_SECRETS_FILE to a real path, or unset UPLOAD_SECRETS."
    exit 1
  fi

  # 检查远端是否已存在 secrets 文件
  REMOTE_HAS_SECRETS=0
  if run_ssh "[ -f '$SECRETS_REMOTE_PATH' ] && echo yes || echo no" | grep -q yes; then
    REMOTE_HAS_SECRETS=1
  fi

  if [[ "$REMOTE_HAS_SECRETS" == "1" ]]; then
    if [[ "$UPLOAD_SECRETS_FORCE" == "1" ]]; then
      echo "[INFO] Remote secrets exist and --force specified: overwriting $SECRETS_REMOTE_PATH"
      run_scp "$SECRETS_FILE" "$CLOUD_USER@$CLOUD_HOST:$SECRETS_REMOTE_PATH"
      run_ssh "chmod 600 '$SECRETS_REMOTE_PATH' || true"
    else
      if [[ "$UPLOAD_SECRETS_MERGE" == "1" ]]; then
        echo "[INFO] 远端已存在 secrets -> 执行合并（保留远端已有的非占位值）"
        TMPDIR="$(mktemp -d)"
        TMP_REMOTE="$TMPDIR/remote.secrets"
        TMP_MERGED="$TMPDIR/merged.secrets"
        # 从远端下载到临时文件
        run_scp "$CLOUD_USER@$CLOUD_HOST:$SECRETS_REMOTE_PATH" "$TMP_REMOTE"

        # 合并逻辑用 Python 实现：保留远端的非占位值；用本地值替换远端的占位符；追加远端缺失的键
        python3 - <<PY "$TMP_REMOTE" "$SECRETS_FILE" "$TMP_MERGED"
import sys,re
remote_path, local_path, out_path = sys.argv[1:4]

def parse(path):
    # 解析文件：返回原始行和键值映射（忽略注释行）
    lines = []
    kv = {}
    with open(path, 'r', encoding='utf-8') as f:
        for ln in f:
            lines.append(ln.rstrip('\n'))
            m = re.match(r'^\s*([^#\s][A-Za-z0-9_.-]*)\s*=\s*(.*)$', ln)
            if m:
                k = m.group(1).strip()
                v = m.group(2).strip()
                kv[k]=v
    return lines, kv

remote_lines, remote_kv = parse(remote_path)
_, local_kv = parse(local_path)

def is_placeholder(v):
    # 判断是否为占位符：以 REPLACE_WITH_ 或 YOUR_ 开头，或为空字符串
    if v is None: return True
    up = v.strip().upper()
    return up.startswith('REPLACE_WITH_') or up.startswith('YOUR_') or up == ''

# 从远端行开始，替换那些在远端为占位符的键；并在末尾追加本地新增键
out_lines = list(remote_lines)
seen = set()
for i, ln in enumerate(out_lines):
    m = re.match(r'^(\s*[^#\s][A-Za-z0-9_.-]*)\s*=\s*(.*)$', ln)
    if m:
        k = m.group(1).strip()
        seen.add(k)
        rv = remote_kv.get(k,'')
        if is_placeholder(rv) and k in local_kv:
            out_lines[i] = f"{k}={local_kv[k]}"

# 追加本地中远端不存在的键
for k, v in local_kv.items():
    if k not in seen:
        out_lines.append(f"{k}={v}")

with open(out_path, 'w', encoding='utf-8') as f:
    f.write('\n'.join(out_lines)+"\n")
PY

        # upload merged file
        run_scp "$TMP_MERGED" "$CLOUD_USER@$CLOUD_HOST:$SECRETS_REMOTE_PATH"
        run_ssh "chmod 600 '$SECRETS_REMOTE_PATH' || true"
        rm -rf "$TMPDIR"
      else
        echo "[INFO] Remote secrets exist and merge disabled; skipping upload. Use UPLOAD_SECRETS_FORCE=1 to overwrite."
      fi
    fi
  else
    echo "[INFO] Remote secrets do not exist -> uploading $SECRETS_REMOTE_PATH"
    run_scp "$SECRETS_FILE" "$CLOUD_USER@$CLOUD_HOST:$SECRETS_REMOTE_PATH"
    run_ssh "chmod 600 '$SECRETS_REMOTE_PATH' || true"
  fi
fi

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
