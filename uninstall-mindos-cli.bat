@echo off
REM MindOS CLI 一键卸载脚本 (Windows)
REM 用法: 双击或在命令行运行 uninstall-mindos-cli.bat

set INSTALL_DIR=%USERPROFILE%\.mindos-cli

if exist "%INSTALL_DIR%" (
  rmdir /s /q "%INSTALL_DIR%"
  echo [MindOS] 已卸载 CLI，目录 %INSTALL_DIR% 已删除。
) else (
  echo [MindOS] 未检测到 CLI 安装目录，无需卸载。
)

