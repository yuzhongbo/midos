#!/bin/bash
# MindOS 服务端一键安装脚本 (macOS/Linux)
# 用法: bash install-mindos-server.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
API_MODULE="assistant-api"
JAR_NAME="assistant-api-0.1.0-SNAPSHOT.jar"
INSTALL_DIR="$HOME/.mindos-server"
ENV_FILE="$INSTALL_DIR/mindos-server.env.sh"

# 构建服务端（默认跑测试，临时加速可导出 SKIP_TESTS=1）
cd "$REPO_DIR"
echo "[MindOS] 构建服务端..."
if [[ "${SKIP_TESTS:-0}" == "1" ]]; then
  ./mvnw -pl $API_MODULE -am clean package -DskipTests
else
  ./mvnw -pl $API_MODULE -am clean package
fi

# 安装目录
mkdir -p "$INSTALL_DIR"
cp "$API_MODULE/target/$JAR_NAME" "$INSTALL_DIR/"

if [[ ! -f "$ENV_FILE" ]]; then
  cp "$REPO_DIR/mindos-server.env.template.sh" "$ENV_FILE"
  chmod +x "$ENV_FILE"
fi

# 创建 secrets 模板（若已存在则保留用户内容）
SECRETS_FILE="$INSTALL_DIR/mindos-secrets.properties"
if [[ ! -f "$SECRETS_FILE" ]]; then
  cat > "$SECRETS_FILE" <<'PROPS'
# 编辑右侧值，# 或 ; 开头为注释
# 推荐：只改 MINDOS_MODEL_PRESET，不要手改 provider map
# 可选值：OPENROUTER_INTENT / QWEN_STABLE / DOUBAO_STABLE / LOCAL_QWEN
MINDOS_MODEL_PRESET=OPENROUTER_INTENT
MINDOS_OPENROUTER_KEY=REPLACE_WITH_OPENROUTER_KEY
MINDOS_QWEN_KEY=REPLACE_WITH_QWEN_KEY
MINDOS_QWEN_MODEL=qwen3.6-plus
MINDOS_DOUBAO_ARK_KEY=
MINDOS_DOUBAO_ENDPOINT_ID=
MINDOS_LOCAL_LLM_ENDPOINT=http://localhost:11434/api/chat
MINDOS_LOCAL_LLM_MODEL=gemma3:1b-it-q4_K_M

# 钉钉 stream 模式（长连接收消息 + 会话回推）
MINDOS_IM_DINGTALK_STREAM_CLIENT_ID=
MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET=
MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE=
MINDOS_IM_DINGTALK_STREAM_WAITING_DELAY_MS=1200
MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=处理中，请稍候。
MINDOS_IM_DINGTALK_STREAM_FORCE_WAITING=false
MINDOS_IM_DINGTALK_MESSAGE_CARD_ENABLED=true
MINDOS_IM_DINGTALK_MESSAGE_UPDATE_ENABLED=true
MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_INTERVAL_MS=250
MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_DELTA_CHARS=24
MINDOS_IM_DINGTALK_AGENT_STATUS_ENABLED=true
MINDOS_IM_DINGTALK_TOKEN_MONITOR_ENABLED=true
MINDOS_IM_DINGTALK_OUTBOUND_UPDATE_URL=https://api.dingtalk.com/v1.0/im/chat/messages/update

# 可选 outbound 覆盖；留空则复用上面的 stream clientId/clientSecret
MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY=
MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET=
MINDOS_IM_DINGTALK_OUTBOUND_SEND_URL=https://api.dingtalk.com/v1.0/im/chat/messages/send

# 兼容旧变量名（若你已有旧配置，可继续保留）
# MINDOS_IM_DINGTALK_APP_KEY=
# MINDOS_IM_DINGTALK_APP_SECRET=
PROPS
fi

# 创建启动脚本，自动加载 secrets
cat > "$INSTALL_DIR/mindos-server" <<'EOF'
#!/bin/bash
set -e
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$ROOT_DIR/mindos-server.env.sh"
JAR_NAME="assistant-api-0.1.0-SNAPSHOT.jar"

export MINDOS_ENV_LOADED=""
if [[ -f "$ENV_FILE" ]]; then
  # shellcheck source=/dev/null
  source "$ENV_FILE"
fi

if [[ "${MINDOS_ENV_LOADED:-}" != "1" ]]; then
  echo "[FAIL] mindos-server.env.sh did not finish loading. Check syntax/encoding in env and secrets files."
  if [[ -n "${MINDOS_ENV_STAGE:-}" ]]; then
    echo "[FAIL] Last env stage: ${MINDOS_ENV_STAGE}"
  fi
  exit 1
fi

java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar "$ROOT_DIR/$JAR_NAME" --spring.profiles.active="$MINDOS_SPRING_PROFILE" "$@"
EOF
chmod +x "$INSTALL_DIR/mindos-server"

# 添加到 PATH 提示
if [[ ":$PATH:" != *":$INSTALL_DIR:"* ]]; then
  echo "[MindOS] 建议将 $INSTALL_DIR 添加到 PATH："
  echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
fi

echo "[MindOS] 服务端安装完成！可用 mindos-server 命令启动。"
echo "[MindOS] 请先编辑 $SECRETS_FILE, 钉钉 stream 模式请填写 CLIENT_ID / CLIENT_SECRET / OUTBOUND_ROBOT_CODE"
echo "[MindOS] 推荐：只改 $SECRETS_FILE 里的 MINDOS_MODEL_PRESET（例如 OPENROUTER_INTENT / LOCAL_QWEN / QWEN_STABLE）"
echo "[MindOS] 可选：通过 MINDOS_IM_DINGTALK_MESSAGE_CARD_ENABLED / MINDOS_IM_DINGTALK_MESSAGE_UPDATE_ENABLED 控制单卡流式更新"
