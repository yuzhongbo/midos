#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

usage() {
  cat <<'USAGE'
Usage:
  ./export-mindos-windows-dist.sh /path/to/output-dir

Environment overrides:
  SKIP_TESTS=1   # default: 1, set to 0 to run tests during package

Output contents:
  assistant-api-0.1.0-SNAPSHOT.jar
  mindos-server.env.bat
  mindos-server.full.env.bat
  mindos-server.bat
  mindos-server-debug.bat
  mindos-server-smoke.bat
  mindos-server-stop.bat
  README-windows-server.txt
  logs/
  run/
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

OUTPUT_DIR="${1:-}"
if [[ -z "$OUTPUT_DIR" ]]; then
  echo "[ERROR] Missing output directory"
  usage
  exit 1
fi

SKIP_TESTS="${SKIP_TESTS:-1}"
OUTPUT_DIR="$(python3 - <<'PY' "$OUTPUT_DIR"
import os, sys
print(os.path.abspath(sys.argv[1]))
PY
)"

OUTPUT_PARENT="$(dirname "$OUTPUT_DIR")"
if [[ ! -d "$OUTPUT_PARENT" ]]; then
  if ! mkdir -p "$OUTPUT_PARENT" 2>/dev/null; then
    echo "[ERROR] Cannot create output parent directory: $OUTPUT_PARENT"
    echo "        It may be read-only or permission denied."
    echo "        Try a writable path, e.g.:"
    echo "        ./export-mindos-windows-dist.sh \"$HOME/dist/mindos-windows-server\""
    exit 1
  fi
fi

if [[ ! -w "$OUTPUT_PARENT" ]]; then
  echo "[ERROR] Output parent directory is not writable: $OUTPUT_PARENT"
  echo "        Try a writable path, e.g.:"
  echo "        ./export-mindos-windows-dist.sh \"$HOME/dist/mindos-windows-server\""
  exit 1
fi

JAR_NAME="assistant-api-0.1.0-SNAPSHOT.jar"
JAR_PATH="$ROOT_DIR/assistant-api/target/$JAR_NAME"

BUILD_CMD=("$ROOT_DIR/mvnw" -q -pl assistant-api -am clean package)
if [[ "$SKIP_TESTS" == "1" ]]; then
  BUILD_CMD+=( -DskipTests )
fi

echo "[INFO] Building assistant-api..."
"${BUILD_CMD[@]}"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "[ERROR] Built jar not found: $JAR_PATH"
  exit 1
fi

mkdir -p "$OUTPUT_DIR/logs" "$OUTPUT_DIR/run"
cp "$JAR_PATH" "$OUTPUT_DIR/$JAR_NAME"

cat > "$OUTPUT_DIR/mindos-server.env.bat" <<'BAT'
@echo off
REM Edit this file on Windows before starting MindOS.

REM Core runtime
set "MINDOS_SPRING_PROFILE=solo"
set "MINDOS_SERVER_PORT=8080"
set "MINDOS_SERVER=http://localhost:8080"
set "MINDOS_USER=solo-smoke-user"
set "MINDOS_SMOKE_TIMEOUT_SECONDS=8"
set "MINDOS_PAUSE_ON_EXIT=true"

REM LLM routing / provider selection
set "MINDOS_LLM_HTTP_ENABLED=true"
set "MINDOS_LLM_PROVIDER=qwen"
set "MINDOS_LLM_PROVIDER_ENDPOINTS=qwen:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
set "MINDOS_LLM_PROVIDER_KEYS=qwen:sk-qwen"

REM Optional cache overrides
REM set "MINDOS_LLM_CACHE_ENABLED=true"
REM set "MINDOS_LLM_CACHE_TTL_SECONDS=120"
REM set "MINDOS_LLM_CACHE_MAX_ENTRIES=512"

REM DingTalk / WeChat bot integration
set "MINDOS_IM_ENABLED=true"
set "MINDOS_IM_DINGTALK_ENABLED=true"
set "MINDOS_IM_DINGTALK_VERIFY_SIGNATURE=false"
set "MINDOS_IM_DINGTALK_SECRET=replace-with-your-dingtalk-signing-secret"
set "MINDOS_IM_DINGTALK_REPLY_TIMEOUT_MS=2500"
REM Optional DingTalk stream mode for slow replies
REM set "MINDOS_IM_DINGTALK_STREAM_ENABLED=true"
REM set "MINDOS_IM_DINGTALK_STREAM_CLIENT_ID=ding-app-key"
REM set "MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET=ding-app-secret"
REM set "MINDOS_IM_DINGTALK_STREAM_TOPIC=chatbot"
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_DELAY_MS=800"
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=我正在处理这条消息，请稍等，我会继续回复你。"
REM set "MINDOS_IM_DINGTALK_OUTBOUND_ENABLED=true"
REM set "MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE=dingrobotcode"
set "MINDOS_IM_WECHAT_ENABLED=false"
set "MINDOS_IM_WECHAT_VERIFY_SIGNATURE=true"
set "MINDOS_IM_WECHAT_TOKEN=replace-with-your-wechat-token"

REM Metrics endpoint auth for local single-user usage
set "MINDOS_SECURITY_METRICS_REQUIRE_ADMIN_TOKEN=false"
BAT

cat > "$OUTPUT_DIR/mindos-server.full.env.bat" <<'BAT'
@echo off
REM Full multi-provider reference for Windows.
REM Copy only the lines you need into mindos-server.env.bat.

REM Core runtime
set "MINDOS_SPRING_PROFILE=solo"
set "MINDOS_SERVER_PORT=8080"
set "MINDOS_SERVER=http://localhost:8080"
set "MINDOS_USER=solo-smoke-user"
set "MINDOS_SMOKE_TIMEOUT_SECONDS=8"
set "MINDOS_PAUSE_ON_EXIT=true"

REM LLM routing / provider selection
set "MINDOS_LLM_HTTP_ENABLED=true"
set "MINDOS_LLM_PROVIDER=qwen"
set "MINDOS_LLM_ROUTING_MODE=fixed"
set "MINDOS_LLM_ROUTING_STAGE_MAP=llm-dsl:qwen,llm-fallback:qwen"
set "MINDOS_LLM_ROUTING_PRESET_MAP=cost:qwen,balanced:qwen,quality:qwen"
set "MINDOS_LLM_PROVIDER_ENDPOINTS=openai:https://ai.2756online.com/openai/v1/chat/completions,gemini:https://ai.2756online.com/gemini/v1beta/models/gemini-2.0-flash:generateContent,qwen:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions,grok:https://ai.2756online.com/grok/v1/chat/completions"
REM Fill in only the providers you really use, for example:
REM set "MINDOS_LLM_PROVIDER_KEYS=qwen:your-qwen-key"
REM set "MINDOS_LLM_PROVIDER_KEYS=deepseek:sk-xxx,openai:sk-yyy,gemini:AIzaSy-zzz,qwen:sk-qwen,grok:sk-aaa"

REM Optional cache overrides
REM set "MINDOS_LLM_CACHE_ENABLED=true"
REM set "MINDOS_LLM_CACHE_TTL_SECONDS=120"
REM set "MINDOS_LLM_CACHE_MAX_ENTRIES=512"

REM DingTalk / WeChat bot integration
set "MINDOS_IM_ENABLED=true"
set "MINDOS_IM_DINGTALK_ENABLED=true"
set "MINDOS_IM_DINGTALK_VERIFY_SIGNATURE=false"
set "MINDOS_IM_DINGTALK_SECRET=replace-with-your-dingtalk-signing-secret"
set "MINDOS_IM_DINGTALK_REPLY_TIMEOUT_MS=2500"
REM Optional DingTalk stream mode for slow replies
REM set "MINDOS_IM_DINGTALK_STREAM_ENABLED=true"
REM set "MINDOS_IM_DINGTALK_STREAM_CLIENT_ID=ding-app-key"
REM set "MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET=ding-app-secret"
REM set "MINDOS_IM_DINGTALK_STREAM_TOPIC=chatbot"
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_DELAY_MS=800"
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=我正在处理这条消息，请稍等，我会继续回复你。"
REM set "MINDOS_IM_DINGTALK_OUTBOUND_ENABLED=true"
REM set "MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE=dingrobotcode"
set "MINDOS_IM_WECHAT_ENABLED=false"
set "MINDOS_IM_WECHAT_VERIFY_SIGNATURE=true"
set "MINDOS_IM_WECHAT_TOKEN=replace-with-your-wechat-token"

REM Metrics endpoint auth for local single-user usage
set "MINDOS_SECURITY_METRICS_REQUIRE_ADMIN_TOKEN=false"
BAT

cat > "$OUTPUT_DIR/mindos-server.bat" <<'BAT'
@echo off
setlocal

set "ROOT_DIR=%~dp0"
cd /d "%ROOT_DIR%"
if exist "%ROOT_DIR%mindos-server.env.bat" call "%ROOT_DIR%mindos-server.env.bat"
if not exist logs mkdir logs
if not exist run mkdir run

set "JAR_NAME=assistant-api-0.1.0-SNAPSHOT.jar"
set "JAR_PATH=%ROOT_DIR%%JAR_NAME%"
set "PID_FILE=%ROOT_DIR%run\assistant-api.pid"
set "LOG_OUT=%ROOT_DIR%logs\assistant-api.out"
set "LOG_ERR=%ROOT_DIR%logs\assistant-api.err"
set "SPRING_PROFILE=%MINDOS_SPRING_PROFILE%"
if "%SPRING_PROFILE%"=="" set "SPRING_PROFILE=solo"
set "SERVER_PORT=%MINDOS_SERVER_PORT%"
if "%SERVER_PORT%"=="" set "SERVER_PORT=8080"
set "PAUSE_ON_EXIT=%MINDOS_PAUSE_ON_EXIT%"
if "%PAUSE_ON_EXIT%"=="" set "PAUSE_ON_EXIT=true"

where java >nul 2>nul
if errorlevel 1 (
  echo [FAIL] Java not found in PATH. Please install Java 17.
  goto :pause_and_fail
)

if not exist "%JAR_PATH%" (
  echo [FAIL] Missing jar: %JAR_PATH%
  goto :pause_and_fail
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop';" ^
  "$pidFile='%PID_FILE%';" ^
  "if (Test-Path $pidFile) { $existing = ((Get-Content $pidFile | Select-Object -First 1) -as [string]).Trim(); if ($existing -and (Get-Process -Id ([int]$existing) -ErrorAction SilentlyContinue)) { Write-Host ('[PASS] MindOS already running (PID=' + $existing + ')'); exit 0 } else { Remove-Item $pidFile -ErrorAction SilentlyContinue } };" ^
  "$args = @('-jar', '%JAR_PATH%', '--spring.profiles.active=%SPRING_PROFILE%', '--server.port=%SERVER_PORT%');" ^
  "$p = Start-Process -FilePath 'java' -ArgumentList $args -WorkingDirectory '%ROOT_DIR%' -RedirectStandardOutput '%LOG_OUT%' -RedirectStandardError '%LOG_ERR%' -PassThru -WindowStyle Hidden;" ^
  "Set-Content -Path $pidFile -Value $p.Id;" ^
  "Start-Sleep -Seconds 2;" ^
  "if (-not (Get-Process -Id $p.Id -ErrorAction SilentlyContinue)) { Write-Host '[FAIL] Startup failed. Check logs\\assistant-api.err'; exit 1 };" ^
  "Write-Host ('[PASS] MindOS started (PID=' + $p.Id + ', port=%SERVER_PORT%)')"
if errorlevel 1 goto :pause_and_fail

echo [INFO] Logs: %LOG_OUT%
exit /b 0

:pause_and_fail
echo [INFO] Check logs: %LOG_ERR%
if /I "%PAUSE_ON_EXIT%"=="true" pause
exit /b 1
BAT

cat > "$OUTPUT_DIR/mindos-server-debug.bat" <<'BAT'
@echo off
setlocal

set "ROOT_DIR=%~dp0"
cd /d "%ROOT_DIR%"
if exist "%ROOT_DIR%mindos-server.env.bat" call "%ROOT_DIR%mindos-server.env.bat"

set "JAR_NAME=assistant-api-0.1.0-SNAPSHOT.jar"
set "JAR_PATH=%ROOT_DIR%%JAR_NAME%"
set "SPRING_PROFILE=%MINDOS_SPRING_PROFILE%"
if "%SPRING_PROFILE%"=="" set "SPRING_PROFILE=solo"
set "SERVER_PORT=%MINDOS_SERVER_PORT%"
if "%SERVER_PORT%"=="" set "SERVER_PORT=8080"

where java >nul 2>nul
if errorlevel 1 (
  echo [FAIL] Java not found in PATH. Please install Java 17.
  pause
  exit /b 1
)

if not exist "%JAR_PATH%" (
  echo [FAIL] Missing jar: %JAR_PATH%
  pause
  exit /b 1
)

echo [INFO] Starting in foreground (debug mode). Press Ctrl+C to stop.
echo [INFO] Profile=%SPRING_PROFILE% Port=%SERVER_PORT%
java -jar "%JAR_PATH%" --spring.profiles.active=%SPRING_PROFILE% --server.port=%SERVER_PORT%
set "EXIT_CODE=%ERRORLEVEL%"
echo [INFO] Process exited with code %EXIT_CODE%
pause
exit /b %EXIT_CODE%
BAT

cat > "$OUTPUT_DIR/mindos-server-smoke.bat" <<'BAT'
@echo off
setlocal

set "ROOT_DIR=%~dp0"
if exist "%ROOT_DIR%mindos-server.env.bat" call "%ROOT_DIR%mindos-server.env.bat"
set "SERVER_URL=%MINDOS_SERVER%"
if "%SERVER_URL%"=="" set "SERVER_URL=http://localhost:8080"
set "USER_ID=%MINDOS_USER%"
if "%USER_ID%"=="" set "USER_ID=solo-smoke-user"
set "TIMEOUT_SECONDS=%MINDOS_SMOKE_TIMEOUT_SECONDS%"
if "%TIMEOUT_SECONDS%"=="" set "TIMEOUT_SECONDS=8"

if "%~1"=="--help" goto :help

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop';" ^
  "$server='%SERVER_URL%'; $user='%USER_ID%'; $timeout=%TIMEOUT_SECONDS%;" ^
  "$chatBody = @{ userId = $user; message = 'echo smoke' } | ConvertTo-Json -Compress;" ^
  "$chat = Invoke-RestMethod -Method Post -Uri ($server + '/chat') -ContentType 'application/json' -Body $chatBody -TimeoutSec $timeout;" ^
  "if ($chat.channel -ne 'echo') { Write-Host '[FAIL] /chat channel expected echo, got:' $chat.channel; exit 1 };" ^
  "Write-Host '[OK] /chat echo route';" ^
  "$metrics = Invoke-RestMethod -Method Get -Uri ($server + '/api/metrics/llm') -TimeoutSec $timeout;" ^
  "if ($null -eq $metrics.windowMinutes) { Write-Host '[FAIL] /api/metrics/llm missing windowMinutes'; exit 1 };" ^
  "Write-Host ('[OK] /api/metrics/llm reachable (windowMinutes=' + $metrics.windowMinutes + ')');" ^
  "Write-Host '[PASS] solo smoke checks completed'"
if errorlevel 1 exit /b 1
exit /b 0

:help
echo Usage:
echo   mindos-server-smoke.bat
echo.
echo Environment overrides:
echo   MINDOS_SERVER=http://localhost:8080
echo   MINDOS_USER=solo-smoke-user
echo   MINDOS_SMOKE_TIMEOUT_SECONDS=8
exit /b 0
BAT

cat > "$OUTPUT_DIR/mindos-server-stop.bat" <<'BAT'
@echo off
setlocal

set "ROOT_DIR=%~dp0"
if exist "%ROOT_DIR%mindos-server.env.bat" call "%ROOT_DIR%mindos-server.env.bat"
set "PID_FILE=%ROOT_DIR%run\assistant-api.pid"
set "PORT=%MINDOS_SERVER_PORT%"
if "%PORT%"=="" set "PORT=%MINDOS_PORT%"
if "%PORT%"=="" set "PORT=8080"
set "FORCE=false"

if /I "%~1"=="--force" set "FORCE=true"
if /I "%~1"=="--help" goto :help

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='SilentlyContinue';" ^
  "$pidFile='%PID_FILE%';" ^
  "$port=%PORT%;" ^
  "$force = ('%FORCE%'.ToLower() -eq 'true');" ^
  "$targets = New-Object System.Collections.Generic.List[int];" ^
  "if (Test-Path $pidFile) { $pidValue = ((Get-Content $pidFile | Select-Object -First 1) -as [string]).Trim(); if ($pidValue -match '^[0-9]+$') { $targets.Add([int]$pidValue) } }" ^
  "try { $targets.AddRange(@(Get-NetTCPConnection -State Listen -LocalPort $port | Select-Object -Expand OwningProcess)) } catch {}" ^
  "try { $targets.AddRange(@(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*assistant-api-0.1.0-SNAPSHOT.jar*' } | Select-Object -Expand ProcessId)) } catch {}" ^
  "$pids = $targets | Where-Object { $_ -and $_ -gt 0 } | Sort-Object -Unique;" ^
  "if (-not $pids) { if (Test-Path $pidFile) { Remove-Item $pidFile -ErrorAction SilentlyContinue }; Write-Host '[PASS] Nothing to stop'; exit 0 }" ^
  "Write-Host ('[INFO] Stopping PID(s): ' + ($pids -join ', '));" ^
  "foreach ($pid in $pids) { Stop-Process -Id $pid -Force:$force -ErrorAction SilentlyContinue }" ^
  "Start-Sleep -Seconds 1;" ^
  "$alive = @(); foreach ($pid in $pids) { if (Get-Process -Id $pid -ErrorAction SilentlyContinue) { $alive += $pid } }" ^
  "if ($alive -and -not $force) { foreach ($pid in $alive) { Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue }; Start-Sleep -Seconds 1; $alive = @(); foreach ($pid in $pids) { if (Get-Process -Id $pid -ErrorAction SilentlyContinue) { $alive += $pid } } }" ^
  "if (Test-Path $pidFile) { Remove-Item $pidFile -ErrorAction SilentlyContinue }" ^
  "if ($alive) { Write-Host ('[FAIL] Still running PID(s): ' + ($alive -join ', ')); exit 1 }" ^
  "Write-Host '[PASS] Service stopped'"
if errorlevel 1 exit /b 1
exit /b 0

:help
echo Usage:
echo   mindos-server-stop.bat
echo   mindos-server-stop.bat --force
echo.
echo Environment overrides:
echo   MINDOS_SERVER_PORT=8080
echo   MINDOS_PORT=8080
exit /b 0
BAT

cat > "$OUTPUT_DIR/README-windows-server.txt" <<'TXT'
MindOS Windows server bundle
============================

1) Edit mindos-server.env.bat before startup:
   - MINDOS_LLM_PROVIDER / MINDOS_LLM_PROVIDER_ENDPOINTS / MINDOS_LLM_PROVIDER_KEYS for the default Qwen setup
   - if you need a broader reference, open mindos-server.full.env.bat and copy the needed lines back
   - MINDOS_IM_DINGTALK_* / MINDOS_IM_WECHAT_* for bot integration
   - MINDOS_SERVER_PORT for local port

2) Start service:
   mindos-server.bat

   If the startup window flashes and closes, run:
   mindos-server-debug.bat

3) Health check:
   mindos-server-smoke.bat

4) Stop service:
   mindos-server-stop.bat

Notes:
- The service starts in background and writes logs to logs\assistant-api.out and logs\assistant-api.err.
- PID file is stored in run\assistant-api.pid.
- Default Spring profile is solo.
- All launcher scripts auto-load mindos-server.env.bat from the same directory.
- mindos-server.full.env.bat is a commented reference file only; it is NOT auto-loaded.
TXT

echo "[DONE] Windows bundle exported to: $OUTPUT_DIR"
echo "       Copy this whole directory to your Windows machine, then run mindos-server.bat"

