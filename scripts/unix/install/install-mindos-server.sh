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
ENV_TEMPLATE="$REPO_DIR/templates/env/mindos-server.env.template.sh"
SECRETS_EXPORT_SCRIPT="$REPO_DIR/scripts/unix/export/export-mindos-secrets.sh"

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
  cp "$ENV_TEMPLATE" "$ENV_FILE"
  chmod +x "$ENV_FILE"
fi

# 创建 secrets 模板（若已存在则保留用户内容）
SECRETS_FILE="$INSTALL_DIR/mindos-secrets.properties"
if [[ ! -f "$SECRETS_FILE" ]]; then
  "$SECRETS_EXPORT_SCRIPT" --template dist "$SECRETS_FILE"
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
echo "[MindOS] 如需按当前 export 环境重新生成，可运行："
echo "  $SECRETS_EXPORT_SCRIPT --force --template dist \"$SECRETS_FILE\""
