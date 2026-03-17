@echo off
REM MindOS 服务端一键卸载脚本 (Windows)
REM 用法: 双击或在命令行运行 uninstall-mindos-server.bat

set INSTALL_DIR=%USERPROFILE%\.mindos-server

if exist "%INSTALL_DIR%" (
  rmdir /s /q "%INSTALL_DIR%"
  echo [MindOS] 已卸载服务端，目录 %INSTALL_DIR% 已删除。
) else (
  echo [MindOS] 未检测到服务端安装目录，无需卸载。
)

