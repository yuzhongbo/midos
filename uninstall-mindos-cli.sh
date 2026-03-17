#!/bin/bash
# MindOS CLI 一键卸载脚本 (macOS/Linux)
# 用法: bash uninstall-mindos-cli.sh

INSTALL_DIR="$HOME/.mindos-cli"

if [ -d "$INSTALL_DIR" ]; then
  rm -rf "$INSTALL_DIR"
  echo "[MindOS] 已卸载 CLI，目录 $INSTALL_DIR 已删除。"
else
  echo "[MindOS] 未检测到 CLI 安装目录，无需卸载。"
fi

