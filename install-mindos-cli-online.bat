@echo off
REM MindOS CLI 在线一键安装脚本 (Windows)
REM 用法: 直接在命令行运行（需已安装 git 和 Java）

set REPO_URL=https://github.com/your-org/MindOS.git
set TMP_DIR=%TEMP%\mindos-cli-%RANDOM%
set INSTALL_DIR=%USERPROFILE%\.mindos-cli

REM 克隆仓库
if exist "%TMP_DIR%" rmdir /s /q "%TMP_DIR%"
git clone --depth=1 %REPO_URL% "%TMP_DIR%"
cd /d "%TMP_DIR%"

REM 构建 CLI
call mvnw -pl mindos-cli -am clean package

REM 安装
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
copy mindos-cli\target\mindos-cli-0.1.0-SNAPSHOT.jar "%INSTALL_DIR%\" /Y
(
echo @echo off
echo java -jar "%INSTALL_DIR%\mindos-cli-0.1.0-SNAPSHOT.jar" %%*
) > "%INSTALL_DIR%\mindos.bat"

REM 清理
cd /d %USERPROFILE%
rmdir /s /q "%TMP_DIR%"

REM PATH 提示
setlocal enabledelayedexpansion
set PATH_CHECK=!PATH:%INSTALL_DIR%=!
if "%PATH_CHECK%"=="%PATH%" (
  echo [MindOS] 建议将 %INSTALL_DIR% 添加到 PATH：
  echo   set PATH=%INSTALL_DIR%;%%PATH%%
)
endlocal

echo [MindOS] CLI 在线安装完成！可用 mindos 命令启动 CLI。

