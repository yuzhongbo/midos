#!/bin/bash
# MindOS 服务端一键安装脚本 (macOS/Linux)
# 用法: bash install-mindos-server.sh

set -e

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
API_MODULE="assistant-api"
JAR_NAME="assistant-api-0.1.0-SNAPSHOT.jar"
INSTALL_DIR="$HOME/.mindos-server"

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

# 创建 secrets 模板（若已存在则保留用户内容）
SECRETS_FILE="$INSTALL_DIR/mindos-secrets.properties"
if [[ ! -f "$SECRETS_FILE" ]]; then
  cat > "$SECRETS_FILE" <<'PROPS'
# 编辑右侧值，# 或 ; 开头为注释
MINDOS_OPENROUTER_KEY=REPLACE_WITH_OPENROUTER_KEY
MINDOS_QWEN_KEY=REPLACE_WITH_QWEN_KEY
MINDOS_QWEN_MODEL=qwen3.5-plus
MINDOS_DOUBAO_ARK_KEY=REPLACE_WITH_DOUBAO_ARK_KEY
MINDOS_DOUBAO_ENDPOINT_ID=REPLACE_WITH_DOUBAO_ENDPOINT_ID

# 可选 IM / webhook，留空即禁用
MINDOS_IM_DINGTALK_APP_KEY=
MINDOS_IM_DINGTALK_APP_SECRET=
MINDOS_IM_DINGTALK_APP_TOKEN=
MINDOS_IM_DINGTALK_APP_AES_KEY=
PROPS
fi

# 创建启动脚本，自动加载 secrets
cat > "$INSTALL_DIR/mindos-server" <<'EOF'
#!/bin/bash
set -e
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SECRETS_FILE="$ROOT_DIR/mindos-secrets.properties"

if [[ -f "$SECRETS_FILE" ]]; then
  while IFS='=' read -r raw_key raw_val; do
    # 跳过注释/空行
    [[ -z "$raw_key" || "$raw_key" =~ ^[[:space:]]*# || "$raw_key" =~ ^[[:space:]]*; ]] && continue
    key="${raw_key%% *}"       # 去除键中的空格（键名不应包含空格）
    val="${raw_val}"
    export "${key}"="${val}"
  done < "$SECRETS_FILE"
fi

java -jar "$ROOT_DIR/$JAR_NAME" "$@"
EOF
chmod +x "$INSTALL_DIR/mindos-server"

# 添加到 PATH 提示
if [[ ":$PATH:" != *":$INSTALL_DIR:"* ]]; then
  echo "[MindOS] 建议将 $INSTALL_DIR 添加到 PATH："
  echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
fi

echo "[MindOS] 服务端安装完成！可用 mindos-server 命令启动。"
echo "[MindOS] 请先编辑 $SECRETS_FILE 填写 LLM/钉钉等密钥，再运行 mindos-server"
