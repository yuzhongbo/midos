@echo off
REM MindOS 服务端一键安装脚本 (Windows)
REM 用法: 双击或在命令行运行 install-mindos-server.bat

setlocal enabledelayedexpansion

set "REPO_DIR=%~dp0"
set "API_MODULE=assistant-api"
set "JAR_NAME=assistant-api-0.1.0-SNAPSHOT.jar"
set "INSTALL_DIR=%USERPROFILE%\.mindos-server"
set "STARTER=%INSTALL_DIR%\mindos-server.bat"
set "SMOKE=%INSTALL_DIR%\mindos-server-smoke.bat"

where java >nul 2>nul
if errorlevel 1 (
  echo [MindOS] 未检测到 Java，请先安装 Java 17 并加入 PATH。
  exit /b 1
)

cd /d "%REPO_DIR%"
call mvnw.cmd -q -pl %API_MODULE% -am clean package
if errorlevel 1 (
  echo [MindOS] 构建失败。
  exit /b 1
)

if not exist "%API_MODULE%\target\%JAR_NAME%" (
  echo [MindOS] 未找到构建产物 %API_MODULE%\target\%JAR_NAME%
  exit /b 1
)

if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
if not exist "%INSTALL_DIR%\logs" mkdir "%INSTALL_DIR%\logs"

copy "%API_MODULE%\target\%JAR_NAME%" "%INSTALL_DIR%\" /Y >nul
if errorlevel 1 (
  echo [MindOS] 复制安装文件失败。
  exit /b 1
)

(
echo @echo off
echo setlocal
echo cd /d "%%~dp0"
echo if not exist logs mkdir logs
echo echo [MindOS] Starting assistant-api with solo profile...
echo java -jar "%INSTALL_DIR%\%JAR_NAME%" --spring.profiles.active=solo %%*
echo exit /b %%ERRORLEVEL%%
) > "%STARTER%"

(
echo @echo off
echo setlocal
echo set "MINDOS_SERVER=%%MINDOS_SERVER%%"
echo if "%%MINDOS_SERVER%%"=="" set "MINDOS_SERVER=http://localhost:8080"
echo powershell -NoProfile -ExecutionPolicy Bypass -Command ^
echo   "$ErrorActionPreference='Stop';" ^
echo   "$server='%%MINDOS_SERVER%%';" ^
echo   "$chat = Invoke-RestMethod -Method Post -Uri ($server + '/chat') -ContentType 'application/json' -Body '{\"userId\":\"solo-smoke-user\",\"message\":\"echo smoke\"}' -TimeoutSec 8;" ^
echo   "if ($chat.channel -ne 'echo') { Write-Host '[FAIL] /chat channel expected echo, got:' $chat.channel; exit 1 };" ^
echo   "$metrics = Invoke-RestMethod -Method Get -Uri ($server + '/api/metrics/llm') -TimeoutSec 8;" ^
echo   "if ($null -eq $metrics.windowMinutes) { Write-Host '[FAIL] /api/metrics/llm missing windowMinutes'; exit 1 };" ^
echo   "Write-Host '[PASS] mindos-server smoke checks completed'"
echo if errorlevel 1 exit /b 1
echo exit /b 0
) > "%SMOKE%"

set "PATH_CHECK=!PATH:%INSTALL_DIR%=!"
if "!PATH_CHECK!"=="!PATH!" (
  echo [MindOS] 建议将 %INSTALL_DIR% 添加到 PATH：
  echo   set PATH=%INSTALL_DIR%;%%PATH%%
)

echo [MindOS] 服务端安装完成！
echo   启动: %INSTALL_DIR%\mindos-server.bat
echo   验活: %INSTALL_DIR%\mindos-server-smoke.bat
echo   建议启动前设置：set MINDOS_LLM_PROVIDER_KEYS=deepseek:...,openai:...,gemini:...,grok:...
endlocal


