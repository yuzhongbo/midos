@echo off
REM MindOS 服务端一键安装脚本 (Windows)
REM 用法: 双击或在命令行运行 install-mindos-server.bat

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "REPO_DIR=%%~fI"
set "API_MODULE=assistant-api"
set "JAR_NAME=assistant-api-0.1.0-SNAPSHOT.jar"
set "INSTALL_DIR=%USERPROFILE%\.mindos-server"
set "STARTER=%INSTALL_DIR%\mindos-server.bat"
set "ENV_FILE=%INSTALL_DIR%\mindos-server.env.bat"
set "SMOKE=%INSTALL_DIR%\mindos-server-smoke.bat"
set "SECRETS_FILE=%INSTALL_DIR%\mindos-secrets.properties"
set "ENV_TEMPLATE=%REPO_DIR%\templates\env\mindos-server.env.template.bat"
set "SECRETS_TEMPLATE=%REPO_DIR%\templates\secrets\mindos-secrets.properties.example"

where java >nul 2>nul
if errorlevel 1 (
  echo [MindOS] 未检测到 Java，请先安装 Java 17 并加入 PATH。
  exit /b 1
)

cd /d "%REPO_DIR%"
set "SKIP_TESTS=%SKIP_TESTS%"
if "%SKIP_TESTS%"=="" set "SKIP_TESTS=0"

if "%SKIP_TESTS%"=="1" (
  call mvnw.cmd -q -pl %API_MODULE% -am clean package -DskipTests
) else (
  call mvnw.cmd -q -pl %API_MODULE% -am clean package
)
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
if not exist "%SECRETS_FILE%" (
  copy "%SECRETS_TEMPLATE%" "%SECRETS_FILE%" /Y >nul
  if errorlevel 1 (
    echo [MindOS] 复制 secrets 模板失败：%SECRETS_TEMPLATE%
    exit /b 1
  )
)

copy "%API_MODULE%\target\%JAR_NAME%" "%INSTALL_DIR%\" /Y >nul
if errorlevel 1 (
  echo [MindOS] 复制安装文件失败。
  exit /b 1
)

if not exist "%ENV_FILE%" (
  copy "%ENV_TEMPLATE%" "%ENV_FILE%" /Y >nul
  if errorlevel 1 (
    echo [MindOS] 复制环境模板失败：%ENV_TEMPLATE%
    exit /b 1
  )
)

(
echo @echo off
echo setlocal EnableExtensions DisableDelayedExpansion
echo cd /d "%%~dp0"
echo set "MINDOS_ENV_LOADED="
echo if exist "%%~dp0mindos-server.env.bat" call "%%~dp0mindos-server.env.bat"
echo if not "%%MINDOS_ENV_LOADED%%"=="1" ^(
echo   echo [FAIL] mindos-server.env.bat did not finish loading. Check quotes/encoding in env and secrets files.
echo   if defined MINDOS_ENV_STAGE echo [FAIL] Last env stage: %%MINDOS_ENV_STAGE%%
echo   exit /b 1
echo ^)
echo if not exist logs mkdir logs
echo echo [MindOS] Starting assistant-api with solo profile...
echo java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar "%INSTALL_DIR%\%JAR_NAME%" --spring.profiles.active=%%MINDOS_SPRING_PROFILE%% %%*
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
echo   建议启动前编辑: %SECRETS_FILE% ^(钉钉 stream 模式填写 CLIENT_ID / CLIENT_SECRET / OUTBOUND_ROBOT_CODE^)
echo   推荐：只改 %SECRETS_FILE% 里的 MINDOS_MODEL_PRESET ^(如 OPENROUTER_INTENT / LOCAL_QWEN / QWEN_STABLE^)
echo   可选：通过 MINDOS_IM_DINGTALK_MESSAGE_CARD_ENABLED / MINDOS_IM_DINGTALK_MESSAGE_UPDATE_ENABLED 控制单卡流式更新
endlocal
