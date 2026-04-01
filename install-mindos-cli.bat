@echo off
REM MindOS CLI 一键安装脚本 (Windows)
REM 用法: 双击或在命令行运行 install-mindos-cli.bat

set REPO_DIR=%~dp0
set CLI_MODULE=mindos-cli
set JAR_NAME=mindos-cli-0.1.0-SNAPSHOT.jar
set INSTALL_DIR=%USERPROFILE%\.mindos-cli

REM 构建 CLI
cd /d %REPO_DIR%
call mvnw -pl %CLI_MODULE% -am clean package

REM 安装目录
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
copy "%CLI_MODULE%\target\%JAR_NAME%" "%INSTALL_DIR%\" /Y

REM 创建启动脚本
set STARTER=%INSTALL_DIR%\mindos.bat
(
echo @echo off
echo java -Dfile.encoding=UTF-8 -jar "%INSTALL_DIR%\%JAR_NAME%" %%*
) > "%STARTER%"

REM 添加到 PATH 提示
setlocal enabledelayedexpansion
set PATH_CHECK=!PATH:%INSTALL_DIR%=!
if "%PATH_CHECK%"=="%PATH%" (
  echo [MindOS] 建议将 %INSTALL_DIR% 添加到 PATH：
  echo   set PATH=%INSTALL_DIR%;%%PATH%%
)
endlocal

echo [MindOS] 安装完成！可用 mindos 命令启动 CLI。

