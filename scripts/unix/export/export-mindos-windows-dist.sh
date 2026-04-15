#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$ROOT_DIR"

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/unix/export/export-mindos-windows-dist.sh [/path/to/output-dir]

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

OUTPUT_DIR="${1:-$ROOT_DIR/dist/mindos-windows-server}"
if [[ $# -eq 0 ]]; then
  echo "[INFO] Output directory not provided; using default: $OUTPUT_DIR"
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
    echo "        ./scripts/unix/export/export-mindos-windows-dist.sh \"$HOME/dist/mindos-windows-server\""
    exit 1
  fi
fi

if [[ ! -w "$OUTPUT_PARENT" ]]; then
  echo "[ERROR] Output parent directory is not writable: $OUTPUT_PARENT"
  echo "        Try a writable path, e.g.:"
  echo "        ./scripts/unix/export/export-mindos-windows-dist.sh \"$HOME/dist/mindos-windows-server\""
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

ENV_TEMPLATE="$ROOT_DIR/templates/env/mindos-server.env.template.bat"
if [[ ! -f "$ENV_TEMPLATE" ]]; then
  echo "[ERROR] Missing template: $ENV_TEMPLATE"
  exit 1
fi
cp "$ENV_TEMPLATE" "$OUTPUT_DIR/mindos-server.env.bat"

"$ROOT_DIR/scripts/unix/export/export-mindos-secrets.sh" --force --template dist "$OUTPUT_DIR/mindos-secrets.properties"

cat > "$OUTPUT_DIR/mindos-server.full.env.bat" <<'BAT'
@echo off
REM Full multi-provider reference for Windows.
REM Copy only the lines you need into mindos-server.env.bat.
REM Keep the set "KEY=value" format unchanged.
REM Recommended: switch models by changing MINDOS_MODEL_PRESET only.

set "MINDOS_ENV_LOADED="

REM Core runtime
set "MINDOS_SPRING_PROFILE=solo"
set "MINDOS_SERVER_PORT=8080"
set "MINDOS_SERVER=http://localhost:8080"
set "MINDOS_MEMORY_FILE_REPO_ENABLED=true"
set "MINDOS_MEMORY_FILE_REPO_BASE_DIR=data/memory-sync"
set "MINDOS_USER=solo-smoke-user"
set "MINDOS_SMOKE_TIMEOUT_SECONDS=8"
set "MINDOS_PAUSE_ON_EXIT=true"

REM Model preset switching
REM   OPENROUTER_INTENT -> gpt+grok+gemini on OpenRouter, optional qwen fallback
REM   QWEN_STABLE       -> qwen only
REM   DOUBAO_STABLE     -> doubao only
REM   LOCAL_QWEN        -> local OpenAI-compatible endpoint first, qwen fallback
REM   CUSTOM            -> manually define provider maps below
set "MINDOS_MODEL_PRESET=OPENROUTER_INTENT"
set "MINDOS_OPENROUTER_KEY=REPLACE_WITH_OPENROUTER_KEY"
set "MINDOS_QWEN_KEY=REPLACE_WITH_QWEN_KEY"
set "MINDOS_QWEN_MODEL=qwen3.6-plus"
set "MINDOS_DOUBAO_ARK_KEY="
set "MINDOS_DOUBAO_ENDPOINT_ID="
set "MINDOS_LOCAL_LLM_ENDPOINT=http://localhost:11434/api/chat"
set "MINDOS_LOCAL_LLM_MODEL=gemma3:1b-it-q4_K_M"
set "MINDOS_LLM_ENDPOINT_OPENROUTER=https://openrouter.ai/api/v1/chat/completions"
set "MINDOS_LLM_ENDPOINT_QWEN=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
set "MINDOS_LLM_ENDPOINT_DOUBAO=https://ark.cn-beijing.volces.com/api/v3/chat/completions"
set "MINDOS_LLM_ENDPOINT_OPENAI=https://api.openai.com/v1/chat/completions"
set "MINDOS_LLM_ENDPOINT_GEMINI=https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
set "MINDOS_LLM_ENDPOINT_GROK=https://api.x.ai/v1/chat/completions"
set "MINDOS_OPENAI_KEY="
set "MINDOS_OPENAI_MODEL=gpt-4.1"
set "MINDOS_GEMINI_KEY="
set "MINDOS_GEMINI_MODEL=gemini-2.5-pro"
set "MINDOS_GROK_KEY="
set "MINDOS_GROK_MODEL=grok-4"

REM Advanced manual maps (only when MINDOS_MODEL_PRESET=CUSTOM)
REM Example manual local/cloud mix:
REM set "MINDOS_LLM_PROVIDER_ENDPOINTS=local:http://localhost:11434/api/chat,qwen:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
REM set "MINDOS_LLM_PROVIDER_KEYS=qwen:sk-xxxx"
REM set "MINDOS_LLM_PROVIDER_MODELS=local:gemma3:1b-it-q4_K_M,qwen:qwen3.6-plus"
REM set "MINDOS_LLM_ROUTING_STAGE_MAP=llm-dsl:local,llm-fallback:qwen"
REM set "MINDOS_LLM_ROUTING_PRESET_MAP=cost:local,balanced:local,quality:qwen"
REM set "MINDOS_LLM_PROVIDER_ENDPOINTS="
REM set "MINDOS_LLM_PROVIDER_KEYS="
REM set "MINDOS_LLM_PROVIDER_MODELS="
REM set "MINDOS_LLM_ROUTING_STAGE_MAP="
REM set "MINDOS_LLM_ROUTING_PRESET_MAP="

REM Safe defaults
set "MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_LOCAL_ESCALATION_ENABLED=false"
set "MINDOS_DISPATCHER_LOCAL_ESCALATION_ENABLED=false"
set "MINDOS_LLM_CACHE_ENABLED=true"
set "MINDOS_LLM_CACHE_TTL_SECONDS=120"
set "MINDOS_LLM_CACHE_MAX_ENTRIES=512"

REM Search / MCP
set "MINDOS_SKILLS_SEARCH_SOURCES="
REM set "MINDOS_SKILLS_MCP_SERVERS="
REM set "MINDOS_SKILLS_MCP_SERVER_HEADERS="

REM DingTalk / WeChat bot integration
set "MINDOS_IM_ENABLED=true"
set "MINDOS_IM_DINGTALK_ENABLED=true"
set "MINDOS_IM_DINGTALK_VERIFY_SIGNATURE=false"
set "MINDOS_IM_DINGTALK_STREAM_CLIENT_ID="
set "MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET="
set "MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE="
set "MINDOS_IM_DINGTALK_STREAM_WAITING_DELAY_MS=1200"
set "MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=处理中，请稍候。"
set "MINDOS_DISPATCHER_LLM_REPLY_MAX_CHARS=700"
set "MINDOS_DISPATCHER_PROMPT_MAX_CHARS=2000"
set "MINDOS_DISPATCHER_MEMORY_CONTEXT_MAX_CHARS=1200"
set "MINDOS_DISPATCHER_MEMORY_CONTEXT_KEEP_RECENT_TURNS=1"
set "MINDOS_IM_WECHAT_ENABLED=false"
set "MINDOS_IM_WECHAT_VERIFY_SIGNATURE=true"
set "MINDOS_IM_WECHAT_TOKEN="

REM Metrics endpoint auth for local single-user usage
set "MINDOS_SECURITY_METRICS_REQUIRE_ADMIN_TOKEN=false"

REM Sentinel: launcher scripts verify this to detect env parsing failures.
set "MINDOS_ENV_LOADED=1"
BAT

cat > "$OUTPUT_DIR/mindos-server.bat" <<'BAT'
@echo off
setlocal EnableExtensions DisableDelayedExpansion

set "ROOT_DIR=%~dp0"
cd /d "%ROOT_DIR%"
set "ENV_FILE_PRESENT="
set "ENV_LOAD_FAILED="
if exist "%ROOT_DIR%mindos-server.env.bat" (
  set "ENV_FILE_PRESENT=1"
  set "MINDOS_ENV_LOADED="
  call "%ROOT_DIR%mindos-server.env.bat"
  if errorlevel 1 set "ENV_LOAD_FAILED=1"
)
if defined ENV_LOAD_FAILED (
  echo [FAIL] mindos-server.env.bat exited with a non-zero code.
  goto :pause_and_fail
)
if defined ENV_FILE_PRESENT if not "%MINDOS_ENV_LOADED%"=="1" (
  echo [FAIL] mindos-server.env.bat did not finish loading. Check for broken quotes/encoding in the file.
  if defined MINDOS_ENV_STAGE echo [FAIL] Last env stage: %MINDOS_ENV_STAGE%
  goto :pause_and_fail
)
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
  "$args = @('-Dfile.encoding=UTF-8', '-jar', '%JAR_PATH%', '--spring.profiles.active=%SPRING_PROFILE%', '--server.port=%SERVER_PORT%');" ^
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
setlocal EnableExtensions DisableDelayedExpansion

if not defined MINDOS_DEBUG_SHELL (
  set "MINDOS_DEBUG_SHELL=1"
  cmd /k ""%~f0" %*"
  exit /b
)

set "ROOT_DIR=%~dp0"
cd /d "%ROOT_DIR%"
set "ENV_FILE_PRESENT="
set "ENV_LOAD_FAILED="
if exist "%ROOT_DIR%mindos-server.env.bat" (
  set "ENV_FILE_PRESENT=1"
  echo [INFO] Loading config from mindos-server.env.bat...
  set "MINDOS_ENV_LOADED="
  call "%ROOT_DIR%mindos-server.env.bat"
  if errorlevel 1 set "ENV_LOAD_FAILED=1"
)
if defined ENV_LOAD_FAILED (
  echo [FAIL] mindos-server.env.bat exited with a non-zero code.
  exit /b 1
)
if defined ENV_FILE_PRESENT if not "%MINDOS_ENV_LOADED%"=="1" (
  echo [FAIL] mindos-server.env.bat did not finish loading. Check for broken quotes/encoding in the file.
  if defined MINDOS_ENV_STAGE echo [FAIL] Last env stage: %MINDOS_ENV_STAGE%
  exit /b 1
)
if defined ENV_FILE_PRESENT (
  echo [INFO] Effective LLM profile: %MINDOS_LLM_PROFILE%
  echo [INFO] Effective LLM mode/provider: %MINDOS_LLM_MODE% / %MINDOS_LLM_PROVIDER%
  echo [INFO] Intent routing enabled: %MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED%
  echo [INFO] DingTalk stream/outbound: %MINDOS_IM_DINGTALK_STREAM_ENABLED% / %MINDOS_IM_DINGTALK_OUTBOUND_ENABLED%
  if /I not "%MINDOS_IM_DINGTALK_STREAM_ENABLED%"=="true" (
    echo [WARN] DingTalk stream mode is disabled. Fill MINDOS_IM_DINGTALK_STREAM_CLIENT_ID and _SECRET in mindos-secrets.properties.
  )
  if /I "%MINDOS_IM_DINGTALK_STREAM_ENABLED%"=="true" if "%MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE%"=="" (
    echo [WARN] DingTalk stream mode is enabled but MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE is blank.
  )
  if /I "%MINDOS_DOUBAO_ARK_KEY%"=="REPLACE_WITH_DOUBAO_ARK_KEY" (
    echo [WARN] MINDOS_DOUBAO_ARK_KEY is still a placeholder.
  )
  if /I "%MINDOS_DOUBAO_ENDPOINT_ID%"=="REPLACE_WITH_DOUBAO_ENDPOINT_ID" (
    echo [WARN] MINDOS_DOUBAO_ENDPOINT_ID is still a placeholder.
  )
  if /I "%MINDOS_QWEN_KEY%"=="REPLACE_WITH_QWEN_KEY" (
    echo [WARN] MINDOS_QWEN_KEY is still a placeholder.
  )
)

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
java -Dfile.encoding=UTF-8 -jar "%JAR_PATH%" --spring.profiles.active=%SPRING_PROFILE% --server.port=%SERVER_PORT%
set "EXIT_CODE=%ERRORLEVEL%"
echo [INFO] Process exited with code %EXIT_CODE%
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

1) Edit only mindos-secrets.properties (KEY=value, # for comment):
   - preferred path: change MINDOS_MODEL_PRESET, then fill the matching key(s)
   - if you use a proxy / gateway / self-hosted URL, override the matching MINDOS_LLM_ENDPOINT_* value too
   - OPENROUTER_INTENT: fill MINDOS_OPENROUTER_KEY, optional MINDOS_QWEN_KEY for fallback
   - QWEN_STABLE: fill MINDOS_QWEN_KEY
   - DOUBAO_STABLE: fill MINDOS_DOUBAO_ARK_KEY + MINDOS_DOUBAO_ENDPOINT_ID
   - OPENAI_NATIVE / GEMINI_NATIVE / GROK_NATIVE: fill the matching native-provider key, optional model override
   - LOCAL_QWEN: fill local endpoint/model if needed, optional MINDOS_QWEN_KEY for fallback
   - CUSTOM: fill MINDOS_LLM_PROVIDER_ENDPOINTS / KEYS / MODELS / ROUTING_* yourself
   - for DingTalk stream mode, fill MINDOS_IM_DINGTALK_STREAM_CLIENT_ID, MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET, and MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE

2) Start service:
   mindos-server.bat

   If the startup window flashes and closes, run:
   mindos-server-debug.bat
   - the debug launcher now reopens itself in a persistent cmd window
   - if mindos-server.env.bat has an error, the debug window will stay open and print the failing exit code

3) Health check:
   mindos-server-smoke.bat

4) Stop service:
   mindos-server-stop.bat

Notes:
- The service starts in background and writes logs to logs\assistant-api.out and logs\assistant-api.err.
- The launcher starts Java with `-Dfile.encoding=UTF-8` by default.
- PID file is stored in run\assistant-api.pid.
- Default Spring profile is solo.
- All launcher scripts auto-load mindos-server.env.bat (which itself loads mindos-secrets.properties if present).
- `mindos-server.full.env.bat` is a copy/paste reference file only; it is NOT auto-loaded.
- Use provider maps only when you intentionally set `MINDOS_MODEL_PRESET=CUSTOM`.
- If startup logs show {"event":"dingtalk.stream.lifecycle.skipped","reason":"stream_mode_disabled"}, the stream credentials or enable flags were not populated.
- Doubao Ark Chat uses Authorization: Bearer <ARK_API_KEY> and requires a real Model ID or Endpoint ID.
- Avoid unescaped special characters in values: & | < > % ^ !
TXT

python3 - <<'PY' "$OUTPUT_DIR"
from pathlib import Path
import sys

output_dir = Path(sys.argv[1])
for path in output_dir.iterdir():
    if path.suffix.lower() not in {".bat", ".properties", ".txt"}:
        continue
    text = path.read_text(encoding="utf-8")
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    with path.open("w", encoding="utf-8", newline="") as handle:
        handle.write(text.replace("\n", "\r\n"))
PY

echo "[DONE] Windows bundle exported to: $OUTPUT_DIR"
echo "       Copy this whole directory to your Windows machine, then run mindos-server.bat"
