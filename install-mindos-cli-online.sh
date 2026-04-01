#!/bin/bash
# MindOS CLI 在线一键安装脚本 (macOS/Linux)
# 用法: bash <(curl -fsSL https://raw.githubusercontent.com/your-org/MindOS/main/install-mindos-cli-online.sh)

set -e

REPO_URL="https://github.com/your-org/MindOS.git"
INSTALL_DIR="$HOME/.mindos-cli"
TMP_DIR="/tmp/mindos-cli-$$"

# 克隆仓库
rm -rf "$TMP_DIR"
git clone --depth=1 "$REPO_URL" "$TMP_DIR"
cd "$TMP_DIR"

# 构建 CLI
./mvnw -pl mindos-cli -am clean package

# 安装
mkdir -p "$INSTALL_DIR"
cp mindos-cli/target/mindos-cli-0.1.0-SNAPSHOT.jar "$INSTALL_DIR/"
cat > "$INSTALL_DIR/mindos" <<EOF
#!/bin/bash
java -Dfile.encoding=UTF-8 -jar "$INSTALL_DIR/mindos-cli-0.1.0-SNAPSHOT.jar" "$@"
EOF
chmod +x "$INSTALL_DIR/mindos"

# 清理
cd ~
rm -rf "$TMP_DIR"

# PATH 提示
if [[ ":$PATH:" != *":$INSTALL_DIR:"* ]]; then
  echo "[MindOS] 建议将 $INSTALL_DIR 添加到 PATH："
  echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
fi

echo "[MindOS] CLI 在线安装完成！可用 mindos 命令启动 CLI。"

