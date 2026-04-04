#!/bin/bash
# MindOS CLI 一键安装脚本 (macOS/Linux)
# 用法: bash install-mindos-cli.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
CLI_MODULE="mindos-cli"
JAR_NAME="mindos-cli-0.1.0-SNAPSHOT.jar"
INSTALL_DIR="$HOME/.mindos-cli"

# 构建 CLI
cd "$REPO_DIR"
echo "[MindOS] 构建 CLI..."
./mvnw -pl $CLI_MODULE -am clean package

# 安装目录
mkdir -p "$INSTALL_DIR"
cp "$CLI_MODULE/target/$JAR_NAME" "$INSTALL_DIR/"

# 创建启动脚本
cat > "$INSTALL_DIR/mindos" <<EOF
#!/bin/bash
java -Dfile.encoding=UTF-8 -jar "$INSTALL_DIR/$JAR_NAME" "$@"
EOF
chmod +x "$INSTALL_DIR/mindos"

# 添加到 PATH 提示
if [[ ":$PATH:" != *":$INSTALL_DIR:"* ]]; then
  echo "[MindOS] 建议将 $INSTALL_DIR 添加到 PATH："
  echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
fi

echo "[MindOS] 安装完成！可用 mindos 命令启动 CLI。"