#!/bin/bash
# MindOS 服务端一键安装脚本 (macOS/Linux)
# 用法: bash install-mindos-server.sh

set -e

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
API_MODULE="assistant-api"
JAR_NAME="assistant-api-0.1.0-SNAPSHOT.jar"
INSTALL_DIR="$HOME/.mindos-server"

# 构建服务端
cd "$REPO_DIR"
echo "[MindOS] 构建服务端..."
./mvnw -pl $API_MODULE -am clean package

# 安装目录
mkdir -p "$INSTALL_DIR"
cp "$API_MODULE/target/$JAR_NAME" "$INSTALL_DIR/"

# 创建启动脚本
cat > "$INSTALL_DIR/mindos-server" <<EOF
#!/bin/bash
java -jar "$INSTALL_DIR/$JAR_NAME" "$@"
EOF
chmod +x "$INSTALL_DIR/mindos-server"

# 添加到 PATH 提示
if [[ ":$PATH:" != *":$INSTALL_DIR:"* ]]; then
  echo "[MindOS] 建议将 $INSTALL_DIR 添加到 PATH："
  echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
fi

echo "[MindOS] 服务端安装完成！可用 mindos-server 命令启动。"

