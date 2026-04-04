#!/bin/bash
# MindOS 服务端一键卸载脚本 (macOS/Linux)
# 用法: bash uninstall-mindos-server.sh

INSTALL_DIR="$HOME/.mindos-server"

if [ -d "$INSTALL_DIR" ]; then
  rm -rf "$INSTALL_DIR"
  echo "[MindOS] 已卸载服务端，目录 $INSTALL_DIR 已删除。"
else
  echo "[MindOS] 未检测到服务端安装目录，无需卸载。"
fi

