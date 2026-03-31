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
REM Minimal config loader. Place secrets in mindos-secrets.properties (KEY=value, # or ; for comments).

set "ROOT_DIR=%~dp0"
set "MINDOS_ENV_LOADED="
set "MINDOS_ENV_STAGE=boot"

if exist "%ROOT_DIR%mindos-secrets.properties" (
  set "MINDOS_ENV_STAGE=loading_secrets"
  for /f "usebackq tokens=1,* delims==" %%A in (`powershell -NoProfile -Command "Get-Content -Path '%ROOT_DIR%mindos-secrets.properties' | Where-Object { $_ -and $_.Trim().Length -gt 0 -and -not $_.Trim().StartsWith('#') -and -not $_.Trim().StartsWith(';') }"`) do (
    if not "%%A"=="" set "%%A=%%B"
  )
)

set "MINDOS_ENV_STAGE=defaults"
if not defined MINDOS_SPRING_PROFILE set "MINDOS_SPRING_PROFILE=solo"
if not defined MINDOS_SERVER_PORT set "MINDOS_SERVER_PORT=8080"
if not defined MINDOS_MEMORY_FILE_REPO_ENABLED set "MINDOS_MEMORY_FILE_REPO_ENABLED=true"
if not defined MINDOS_MEMORY_FILE_REPO_BASE_DIR set "MINDOS_MEMORY_FILE_REPO_BASE_DIR=data/memory-sync"
if not defined MINDOS_PAUSE_ON_EXIT set "MINDOS_PAUSE_ON_EXIT=true"

REM LLM routing profile (override these in mindos-secrets.properties when needed)
if not defined MINDOS_LLM_PROFILE set "MINDOS_LLM_PROFILE=QWEN_STABLE"
if not defined MINDOS_LLM_MODE set "MINDOS_LLM_MODE=QWEN_NATIVE"
if not defined MINDOS_LLM_PROVIDER set "MINDOS_LLM_PROVIDER=qwen"
if not defined MINDOS_LLM_ROUTING_MODE set "MINDOS_LLM_ROUTING_MODE=fixed"
if not defined MINDOS_LLM_ROUTING_STAGE_MAP set "MINDOS_LLM_ROUTING_STAGE_MAP=llm-dsl:qwen,llm-fallback:qwen"
if not defined MINDOS_LLM_ROUTING_PRESET_MAP set "MINDOS_LLM_ROUTING_PRESET_MAP=cost:qwen,balanced:qwen,quality:qwen"
if not defined MINDOS_LLM_ENDPOINT_QWEN set "MINDOS_LLM_ENDPOINT_QWEN=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
if not defined MINDOS_LLM_ENDPOINT_DOUBAO set "MINDOS_LLM_ENDPOINT_DOUBAO=https://ark.cn-beijing.volces.com/api/v3/chat/completions"
if not defined MINDOS_LLM_ENDPOINT_OPENROUTER set "MINDOS_LLM_ENDPOINT_OPENROUTER=https://openrouter.ai/api/v1/chat/completions"
if not defined MINDOS_LLM_ENDPOINT_OPENAI set "MINDOS_LLM_ENDPOINT_OPENAI=https://api.openai.com/v1/chat/completions"
if not defined MINDOS_LLM_ENDPOINT_GEMINI set "MINDOS_LLM_ENDPOINT_GEMINI=https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent"
if not defined MINDOS_LLM_ENDPOINT_GROK set "MINDOS_LLM_ENDPOINT_GROK=https://api.x.ai/v1/chat/completions"
if not defined MINDOS_OPENROUTER_KEY set "MINDOS_OPENROUTER_KEY=REPLACE_WITH_OPENROUTER_KEY"
if not defined MINDOS_OPENAI_KEY set "MINDOS_OPENAI_KEY=REPLACE_WITH_OPENAI_KEY"
if not defined MINDOS_GEMINI_KEY set "MINDOS_GEMINI_KEY=REPLACE_WITH_GEMINI_KEY"
if not defined MINDOS_GROK_KEY set "MINDOS_GROK_KEY=REPLACE_WITH_GROK_KEY"
if not defined MINDOS_QWEN_KEY set "MINDOS_QWEN_KEY=REPLACE_WITH_QWEN_KEY"
if not defined MINDOS_OPENAI_MODEL set "MINDOS_OPENAI_MODEL=gpt-4.1"
if not defined MINDOS_GEMINI_MODEL set "MINDOS_GEMINI_MODEL=gemini-2.5-pro"
if not defined MINDOS_GROK_MODEL set "MINDOS_GROK_MODEL=grok-4"
if not defined MINDOS_QWEN_MODEL set "MINDOS_QWEN_MODEL=qwen3.5-plus"
if not defined MINDOS_DOUBAO_ARK_KEY set "MINDOS_DOUBAO_ARK_KEY=REPLACE_WITH_DOUBAO_ARK_KEY"
if not defined MINDOS_DOUBAO_ENDPOINT_ID set "MINDOS_DOUBAO_ENDPOINT_ID=REPLACE_WITH_DOUBAO_ENDPOINT_ID"
if not defined MINDOS_LLM_PROVIDER_ENDPOINTS set "MINDOS_LLM_PROVIDER_ENDPOINTS=gpt:https://openrouter.ai/api/v1/chat/completions,grok:https://openrouter.ai/api/v1/chat/completions,gemini:https://openrouter.ai/api/v1/chat/completions"
if not defined MINDOS_LLM_PROVIDER_KEYS set "MINDOS_LLM_PROVIDER_KEYS=gpt:%MINDOS_OPENROUTER_KEY%,grok:%MINDOS_OPENROUTER_KEY%,gemini:%MINDOS_OPENROUTER_KEY%,doubao:%MINDOS_DOUBAO_ARK_KEY%,qwen:%MINDOS_QWEN_KEY%"
if not defined MINDOS_LLM_PROVIDER_MODELS set "MINDOS_LLM_PROVIDER_MODELS=gpt:openai/gpt-5.2,grok:x-ai/grok-4,gemini:google/gemini-2.5-pro,doubao:%MINDOS_DOUBAO_ENDPOINT_ID%,qwen:%MINDOS_QWEN_MODEL%"
if not defined MINDOS_SECURITY_METRICS_REQUIRE_ADMIN_TOKEN set "MINDOS_SECURITY_METRICS_REQUIRE_ADMIN_TOKEN=false"
set "MINDOS_INTENT_ROUTING_EXPLICIT="
if defined MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED set "MINDOS_INTENT_ROUTING_EXPLICIT=1"
if not defined MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=false"
if not defined MINDOS_DISPATCHER_SKILL_PRE_ANALYZE_MODE set "MINDOS_DISPATCHER_SKILL_PRE_ANALYZE_MODE=never"

REM Optional IM credentials (leave blank to disable)
if not defined MINDOS_IM_ENABLED set "MINDOS_IM_ENABLED=true"
if not defined MINDOS_IM_DINGTALK_ENABLED set "MINDOS_IM_DINGTALK_ENABLED=true"
if not defined MINDOS_IM_DINGTALK_VERIFY_SIGNATURE set "MINDOS_IM_DINGTALK_VERIFY_SIGNATURE=false"
if not defined MINDOS_IM_DINGTALK_REPLY_TIMEOUT_MS set "MINDOS_IM_DINGTALK_REPLY_TIMEOUT_MS=2500"
if not defined MINDOS_IM_DINGTALK_REPLY_MAX_CHARS set "MINDOS_IM_DINGTALK_REPLY_MAX_CHARS=1200"
if not defined MINDOS_IM_DINGTALK_STREAM_CLIENT_ID set "MINDOS_IM_DINGTALK_STREAM_CLIENT_ID="
if not defined MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET set "MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET="
if not defined MINDOS_IM_DINGTALK_STREAM_TOPIC set "MINDOS_IM_DINGTALK_STREAM_TOPIC=/v1.0/im/bot/messages/get"
if not defined MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT set "MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=已收到，正在处理，稍后给你完整回复。"
if not defined MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE set "MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE="
if not defined MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY set "MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY="
if not defined MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET set "MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET="

REM Backward-compatible aliases for older secrets templates.
if defined MINDOS_IM_DINGTALK_APP_KEY if not defined MINDOS_IM_DINGTALK_STREAM_CLIENT_ID set "MINDOS_IM_DINGTALK_STREAM_CLIENT_ID=%MINDOS_IM_DINGTALK_APP_KEY%"
if defined MINDOS_IM_DINGTALK_APP_SECRET if not defined MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET set "MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET=%MINDOS_IM_DINGTALK_APP_SECRET%"
if defined MINDOS_IM_DINGTALK_APP_KEY if not defined MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY set "MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY=%MINDOS_IM_DINGTALK_APP_KEY%"
if defined MINDOS_IM_DINGTALK_APP_SECRET if not defined MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET set "MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET=%MINDOS_IM_DINGTALK_APP_SECRET%"

REM Reuse stream credentials for outbound unless explicit outbound overrides are provided.
if not defined MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY if defined MINDOS_IM_DINGTALK_STREAM_CLIENT_ID set "MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY=%MINDOS_IM_DINGTALK_STREAM_CLIENT_ID%"
if not defined MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET if defined MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET set "MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET=%MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET%"

REM Auto-enable stream mode when the required secrets are present.
if not defined MINDOS_IM_DINGTALK_STREAM_ENABLED set "MINDOS_IM_DINGTALK_STREAM_ENABLED=false"
if /I "%MINDOS_IM_DINGTALK_STREAM_ENABLED%"=="false" if defined MINDOS_IM_DINGTALK_STREAM_CLIENT_ID if defined MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET set "MINDOS_IM_DINGTALK_STREAM_ENABLED=true"

REM Auto-enable outbound delivery when the required values are present.
if not defined MINDOS_IM_DINGTALK_OUTBOUND_ENABLED set "MINDOS_IM_DINGTALK_OUTBOUND_ENABLED=false"
if /I "%MINDOS_IM_DINGTALK_OUTBOUND_ENABLED%"=="false" if defined MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE if defined MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY if defined MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET set "MINDOS_IM_DINGTALK_OUTBOUND_ENABLED=true"

REM Provider profile switch (manual and predictable)
if /I "%MINDOS_LLM_PROFILE%"=="DOUBAO_STABLE" (
  set "MINDOS_LLM_MODE=DOUBAO_NATIVE"
  set "MINDOS_LLM_PROVIDER=doubao"
  if not defined MINDOS_INTENT_ROUTING_EXPLICIT set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=false"
  set "MINDOS_LLM_ROUTING_STAGE_MAP=llm-dsl:doubao,llm-fallback:doubao"
  set "MINDOS_LLM_ROUTING_PRESET_MAP=cost:doubao,balanced:doubao,quality:doubao"
  set "MINDOS_LLM_PROVIDER_ENDPOINTS=doubao:%MINDOS_LLM_ENDPOINT_DOUBAO%"
  set "MINDOS_LLM_PROVIDER_KEYS=doubao:%MINDOS_DOUBAO_ARK_KEY%"
  set "MINDOS_LLM_PROVIDER_MODELS=doubao:%MINDOS_DOUBAO_ENDPOINT_ID%"
)
if /I "%MINDOS_LLM_PROFILE%"=="QWEN_STABLE" (
  set "MINDOS_LLM_MODE=QWEN_NATIVE"
  set "MINDOS_LLM_PROVIDER=qwen"
  if not defined MINDOS_INTENT_ROUTING_EXPLICIT set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=false"
  set "MINDOS_LLM_ROUTING_STAGE_MAP=llm-dsl:qwen,llm-fallback:qwen"
  set "MINDOS_LLM_ROUTING_PRESET_MAP=cost:qwen,balanced:qwen,quality:qwen"
  set "MINDOS_LLM_PROVIDER_ENDPOINTS=qwen:%MINDOS_LLM_ENDPOINT_QWEN%"
  set "MINDOS_LLM_PROVIDER_KEYS=qwen:%MINDOS_QWEN_KEY%"
  set "MINDOS_LLM_PROVIDER_MODELS=qwen:%MINDOS_QWEN_MODEL%"
)
if /I "%MINDOS_LLM_PROFILE%"=="CN_DUAL" (
  set "MINDOS_LLM_MODE=CN_DUAL_NATIVE"
  set "MINDOS_LLM_PROVIDER=qwen"
  if not defined MINDOS_INTENT_ROUTING_EXPLICIT set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=false"
  set "MINDOS_LLM_ROUTING_STAGE_MAP=llm-dsl:qwen,llm-fallback:doubao"
  set "MINDOS_LLM_ROUTING_PRESET_MAP=cost:qwen,balanced:qwen,quality:doubao"
  set "MINDOS_LLM_PROVIDER_ENDPOINTS=qwen:%MINDOS_LLM_ENDPOINT_QWEN%,doubao:%MINDOS_LLM_ENDPOINT_DOUBAO%"
  set "MINDOS_LLM_PROVIDER_KEYS=qwen:%MINDOS_QWEN_KEY%,doubao:%MINDOS_DOUBAO_ARK_KEY%"
  set "MINDOS_LLM_PROVIDER_MODELS=qwen:%MINDOS_QWEN_MODEL%,doubao:%MINDOS_DOUBAO_ENDPOINT_ID%"
)
if /I "%MINDOS_LLM_PROFILE%"=="OPENROUTER_INTENT" (
  set "MINDOS_LLM_MODE=OPENROUTER"
  set "MINDOS_LLM_PROVIDER=gpt"
  if not defined MINDOS_INTENT_ROUTING_EXPLICIT set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=true"
  set "MINDOS_LLM_PROVIDER_ENDPOINTS=gpt:%MINDOS_LLM_ENDPOINT_OPENROUTER%,grok:%MINDOS_LLM_ENDPOINT_OPENROUTER%,gemini:%MINDOS_LLM_ENDPOINT_OPENROUTER%"
  set "MINDOS_LLM_PROVIDER_KEYS=gpt:%MINDOS_OPENROUTER_KEY%,grok:%MINDOS_OPENROUTER_KEY%,gemini:%MINDOS_OPENROUTER_KEY%"
  set "MINDOS_LLM_PROVIDER_MODELS=gpt:openai/gpt-5.2,grok:x-ai/grok-4,gemini:google/gemini-2.5-pro"
)
if /I "%MINDOS_LLM_PROFILE%"=="OPENAI_NATIVE" (
  set "MINDOS_LLM_MODE=OPENAI_NATIVE"
  set "MINDOS_LLM_PROVIDER=openai"
  if not defined MINDOS_INTENT_ROUTING_EXPLICIT set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=false"
  set "MINDOS_LLM_ROUTING_STAGE_MAP=llm-dsl:openai,llm-fallback:openai"
  set "MINDOS_LLM_ROUTING_PRESET_MAP=cost:openai,balanced:openai,quality:openai"
  set "MINDOS_LLM_PROVIDER_ENDPOINTS=openai:%MINDOS_LLM_ENDPOINT_OPENAI%"
  set "MINDOS_LLM_PROVIDER_KEYS=openai:%MINDOS_OPENAI_KEY%"
  set "MINDOS_LLM_PROVIDER_MODELS=openai:%MINDOS_OPENAI_MODEL%"
)
if /I "%MINDOS_LLM_PROFILE%"=="GEMINI_NATIVE" (
  set "MINDOS_LLM_MODE=GEMINI_NATIVE"
  set "MINDOS_LLM_PROVIDER=gemini"
  if not defined MINDOS_INTENT_ROUTING_EXPLICIT set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=false"
  set "MINDOS_LLM_ROUTING_STAGE_MAP=llm-dsl:gemini,llm-fallback:gemini"
  set "MINDOS_LLM_ROUTING_PRESET_MAP=cost:gemini,balanced:gemini,quality:gemini"
  set "MINDOS_LLM_PROVIDER_ENDPOINTS=gemini:%MINDOS_LLM_ENDPOINT_GEMINI%"
  set "MINDOS_LLM_PROVIDER_KEYS=gemini:%MINDOS_GEMINI_KEY%"
  set "MINDOS_LLM_PROVIDER_MODELS=gemini:%MINDOS_GEMINI_MODEL%"
)
if /I "%MINDOS_LLM_PROFILE%"=="GROK_NATIVE" (
  set "MINDOS_LLM_MODE=GROK_NATIVE"
  set "MINDOS_LLM_PROVIDER=grok"
  if not defined MINDOS_INTENT_ROUTING_EXPLICIT set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=false"
  set "MINDOS_LLM_ROUTING_STAGE_MAP=llm-dsl:grok,llm-fallback:grok"
  set "MINDOS_LLM_ROUTING_PRESET_MAP=cost:grok,balanced:grok,quality:grok"
  set "MINDOS_LLM_PROVIDER_ENDPOINTS=grok:%MINDOS_LLM_ENDPOINT_GROK%"
  set "MINDOS_LLM_PROVIDER_KEYS=grok:%MINDOS_GROK_KEY%"
  set "MINDOS_LLM_PROVIDER_MODELS=grok:%MINDOS_GROK_MODEL%"
)

set "MINDOS_ENV_STAGE=done"
set "MINDOS_ENV_LOADED=1"
BAT

cat > "$OUTPUT_DIR/mindos-secrets.properties" <<'PROPS'
# Only edit the values on the right side. Lines starting with # or ; are ignored.
# LLM providers
# Profile switch (recommended):
# - QWEN_STABLE / DOUBAO_STABLE / CN_DUAL (mainland vendors)
# - OPENAI_NATIVE / GEMINI_NATIVE / GROK_NATIVE (native single-vendor)
# - OPENROUTER_INTENT (OpenRouter multi-vendor intent routing)
MINDOS_LLM_PROFILE=QWEN_STABLE
# Optional manual override for display/debug (normally auto-set by profile)
# MINDOS_LLM_MODE=QWEN_NATIVE
# Optional explicit override (wins over profile default)
# MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=false

# Optional endpoint overrides
# MINDOS_LLM_ENDPOINT_QWEN=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
# MINDOS_LLM_ENDPOINT_DOUBAO=https://ark.cn-beijing.volces.com/api/v3/chat/completions
# MINDOS_LLM_ENDPOINT_OPENROUTER=https://openrouter.ai/api/v1/chat/completions
# MINDOS_LLM_ENDPOINT_OPENAI=https://api.openai.com/v1/chat/completions
# MINDOS_LLM_ENDPOINT_GEMINI=https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent
# MINDOS_LLM_ENDPOINT_GROK=https://api.x.ai/v1/chat/completions

MINDOS_OPENROUTER_KEY=REPLACE_WITH_OPENROUTER_KEY
MINDOS_OPENAI_KEY=REPLACE_WITH_OPENAI_KEY
MINDOS_GEMINI_KEY=REPLACE_WITH_GEMINI_KEY
MINDOS_GROK_KEY=REPLACE_WITH_GROK_KEY
MINDOS_QWEN_KEY=REPLACE_WITH_QWEN_KEY
MINDOS_OPENAI_MODEL=gpt-4.1
MINDOS_GEMINI_MODEL=gemini-2.5-pro
MINDOS_GROK_MODEL=grok-4
MINDOS_QWEN_MODEL=qwen3.5-plus
MINDOS_DOUBAO_ARK_KEY=REPLACE_WITH_DOUBAO_ARK_KEY
MINDOS_DOUBAO_ENDPOINT_ID=REPLACE_WITH_DOUBAO_ENDPOINT_ID

# DingTalk stream mode (fill these three for long-connection bot replies)
# DingTalk console mapping:
# - Client ID (old AppKey / SuiteKey) -> MINDOS_IM_DINGTALK_STREAM_CLIENT_ID
# - Client Secret (old AppSecret / SuiteSecret) -> MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET
# - Robot Code -> MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE
# Note: AgentId/App ID is not read directly by the current stream integration.
MINDOS_IM_DINGTALK_STREAM_CLIENT_ID=
MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET=
MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE=

# Optional outbound overrides; leave blank to reuse the stream clientId/clientSecret above.
MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY=
MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET=

# Legacy aliases are still accepted by mindos-server.env.bat if you already used them.
# MINDOS_IM_DINGTALK_APP_KEY=
# MINDOS_IM_DINGTALK_APP_SECRET=
PROPS

cat > "$OUTPUT_DIR/mindos-server.full.env.bat" <<'BAT'
@echo off
REM Full multi-provider reference for Windows.
REM Copy only the lines you need into mindos-server.env.bat.
REM Keep the set "KEY=value" format unchanged.
REM In provider maps, commas split entries and the first colon splits provider name from value.
REM Edit one line at a time and avoid special CMD symbols unless you know escaping rules.

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

REM LLM routing / provider selection
REM Preset switch:
REM   OPENROUTER_INTENT -> coding=gpt, realtime/news=grok, emotional=gemini
REM   DOUBAO_STABLE     -> single-provider doubao (fast troubleshooting baseline)
REM   QWEN_STABLE       -> single-provider qwen (safe fallback)
set "MINDOS_LLM_PROFILE=OPENROUTER_INTENT"

REM Shared provider credentials/model ids (edit once)
set "MINDOS_OPENROUTER_KEY=REPLACE_WITH_OPENROUTER_KEY"
set "MINDOS_DOUBAO_ARK_KEY=REPLACE_WITH_DOUBAO_ARK_KEY"
set "MINDOS_DOUBAO_ENDPOINT_ID=REPLACE_WITH_DOUBAO_ENDPOINT_ID"
set "MINDOS_QWEN_KEY=REPLACE_WITH_QWEN_KEY"
set "MINDOS_QWEN_MODEL=qwen3.5-plus"

set "MINDOS_LLM_HTTP_ENABLED=true"
set "MINDOS_LLM_MODE=OPENROUTER"
set "MINDOS_LLM_PROVIDER=gpt"
set "MINDOS_LLM_ROUTING_MODE=fixed"
set "MINDOS_LLM_RETRY_MAX_ATTEMPTS=1"
set "MINDOS_LLM_RETRY_DELAY_MS=0"
set "MINDOS_LLM_PROVIDER_ENDPOINTS=gpt:https://openrouter.ai/api/v1/chat/completions,grok:https://openrouter.ai/api/v1/chat/completions,gemini:https://openrouter.ai/api/v1/chat/completions"
set "MINDOS_LLM_PROVIDER_KEYS=gpt:%MINDOS_OPENROUTER_KEY%,grok:%MINDOS_OPENROUTER_KEY%,gemini:%MINDOS_OPENROUTER_KEY%,doubao:%MINDOS_DOUBAO_ARK_KEY%,qwen:%MINDOS_QWEN_KEY%"
set "MINDOS_LLM_PROVIDER_MODELS=gpt:openai/gpt-5.2,grok:x-ai/grok-4,gemini:google/gemini-2.5-pro,doubao:%MINDOS_DOUBAO_ENDPOINT_ID%,qwen:%MINDOS_QWEN_MODEL%"

REM Intent + difficulty based model routing (coding->gpt, realtime/news->grok, emotional->gemini)
set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=true"
set "MINDOS_DISPATCHER_INTENT_ROUTING_DEFAULT_PROVIDER=gpt"
set "MINDOS_DISPATCHER_INTENT_ROUTING_CODE_PROVIDER=gpt"
set "MINDOS_DISPATCHER_INTENT_ROUTING_REALTIME_PROVIDER=grok"
set "MINDOS_DISPATCHER_INTENT_ROUTING_EMOTIONAL_PROVIDER=gemini"
set "MINDOS_DISPATCHER_INTENT_ROUTING_MODEL_CODE_EASY=openai/gpt-5-mini"
set "MINDOS_DISPATCHER_INTENT_ROUTING_MODEL_CODE_MEDIUM=openai/gpt-5.2"
set "MINDOS_DISPATCHER_INTENT_ROUTING_MODEL_CODE_HARD=openai/gpt-5.2"
set "MINDOS_DISPATCHER_INTENT_ROUTING_MODEL_REALTIME_EASY=x-ai/grok-4-fast"
set "MINDOS_DISPATCHER_INTENT_ROUTING_MODEL_REALTIME_MEDIUM=x-ai/grok-4"
set "MINDOS_DISPATCHER_INTENT_ROUTING_MODEL_REALTIME_HARD=x-ai/grok-4"
set "MINDOS_DISPATCHER_INTENT_ROUTING_MODEL_EMOTIONAL_EASY=google/gemini-2.5-flash"
set "MINDOS_DISPATCHER_INTENT_ROUTING_MODEL_EMOTIONAL_MEDIUM=google/gemini-2.5-pro"
set "MINDOS_DISPATCHER_INTENT_ROUTING_MODEL_EMOTIONAL_HARD=google/gemini-2.5-pro"
set "MINDOS_DISPATCHER_INTENT_ROUTING_MODEL_GENERAL_EASY=openai/gpt-5-mini"
set "MINDOS_DISPATCHER_INTENT_ROUTING_MODEL_GENERAL_MEDIUM=openai/gpt-5.2"
set "MINDOS_DISPATCHER_INTENT_ROUTING_MODEL_GENERAL_HARD=openai/gpt-5.2"
set "MINDOS_DISPATCHER_INTENT_ROUTING_HARD_INPUT_LENGTH_THRESHOLD=180"
set "MINDOS_DISPATCHER_SKILL_PRE_ANALYZE_MODE=never"
set "MINDOS_DISPATCHER_SKILL_FINALIZE_WITH_LLM_ENABLED=false"
set "MINDOS_SKILL_CODE_GENERATE_LLM_PROVIDER=gpt"
set "MINDOS_SKILL_CODE_GENERATE_MODEL_EASY=openai/gpt-5-mini"
set "MINDOS_SKILL_CODE_GENERATE_MODEL_MEDIUM=openai/gpt-5.2"
set "MINDOS_SKILL_CODE_GENERATE_MODEL_HARD=openai/gpt-5.2"

if /I "%MINDOS_LLM_PROFILE%"=="DOUBAO_STABLE" (
  set "MINDOS_LLM_MODE=DOUBAO"
  set "MINDOS_LLM_PROVIDER=doubao"
  set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=false"
  set "MINDOS_SKILL_CODE_GENERATE_LLM_PROVIDER=doubao"
  set "MINDOS_SKILL_CODE_GENERATE_MODEL_EASY=%MINDOS_DOUBAO_ENDPOINT_ID%"
  set "MINDOS_SKILL_CODE_GENERATE_MODEL_MEDIUM=%MINDOS_DOUBAO_ENDPOINT_ID%"
  set "MINDOS_SKILL_CODE_GENERATE_MODEL_HARD=%MINDOS_DOUBAO_ENDPOINT_ID%"
)

REM Auto-fallback guard: if Doubao profile is selected but required values are still placeholders,
REM switch to qwen to avoid repeated auth/missing-model degraded replies.
if /I "%MINDOS_LLM_PROFILE%"=="DOUBAO_STABLE" if "%MINDOS_DOUBAO_ARK_KEY%"=="REPLACE_WITH_DOUBAO_ARK_KEY" set "MINDOS_LLM_PROFILE=QWEN_STABLE"
if /I "%MINDOS_LLM_PROFILE%"=="DOUBAO_STABLE" if "%MINDOS_DOUBAO_ENDPOINT_ID%"=="REPLACE_WITH_DOUBAO_ENDPOINT_ID" set "MINDOS_LLM_PROFILE=QWEN_STABLE"

if /I "%MINDOS_LLM_PROFILE%"=="QWEN_STABLE" (
  set "MINDOS_LLM_MODE=OPENROUTER"
  set "MINDOS_LLM_PROVIDER=qwen"
  set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=false"
  set "MINDOS_SKILL_CODE_GENERATE_LLM_PROVIDER=qwen"
  set "MINDOS_SKILL_CODE_GENERATE_MODEL_EASY=%MINDOS_QWEN_MODEL%"
  set "MINDOS_SKILL_CODE_GENERATE_MODEL_MEDIUM=%MINDOS_QWEN_MODEL%"
  set "MINDOS_SKILL_CODE_GENERATE_MODEL_HARD=%MINDOS_QWEN_MODEL%"
)
REM Multi-provider map example (more fragile because commas split entries):
REM set "MINDOS_LLM_PROVIDER_ENDPOINTS=openai:https://ai.example.com/openai/v1/chat/completions,gemini:https://ai.example.com/gemini/v1beta/models/gemini-2.0-flash:generateContent,gpt:https://openrouter.ai/api/v1/chat/completions"
REM set "MINDOS_LLM_PROVIDER_KEYS=openai:paste-openai-key-here,gemini:paste-gemini-key-here,gpt:paste-openrouter-key-here"

REM Optional cache overrides
set "MINDOS_LLM_CACHE_ENABLED=true"
set "MINDOS_LLM_CACHE_TTL_SECONDS=120"
set "MINDOS_LLM_CACHE_MAX_ENTRIES=512"

REM DingTalk / WeChat bot integration
set "MINDOS_IM_ENABLED=true"
set "MINDOS_IM_DINGTALK_ENABLED=true"
set "MINDOS_IM_DINGTALK_VERIFY_SIGNATURE=false"
set "MINDOS_IM_DINGTALK_SECRET="
set "MINDOS_IM_DINGTALK_REPLY_TIMEOUT_MS=2500"
set "MINDOS_IM_DINGTALK_REPLY_MAX_CHARS=1200"

REM Stream required fields (replace placeholders)
set "MINDOS_IM_DINGTALK_STREAM_ENABLED=true"
set "MINDOS_IM_DINGTALK_STREAM_CLIENT_ID=REPLACE_WITH_YOUR_APP_KEY"
set "MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET=REPLACE_WITH_YOUR_APP_SECRET"
set "MINDOS_IM_DINGTALK_STREAM_TOPIC=/v1.0/im/bot/messages/get"
set "MINDOS_IM_DINGTALK_STREAM_RECONNECT_ENABLED=true"
set "MINDOS_IM_DINGTALK_STREAM_RECONNECT_INITIAL_DELAY_MS=1000"
set "MINDOS_IM_DINGTALK_STREAM_RECONNECT_MAX_DELAY_MS=60000"
set "MINDOS_IM_DINGTALK_STREAM_RECONNECT_MULTIPLIER=2.0"
set "MINDOS_IM_DINGTALK_STREAM_RECONNECT_JITTER_RATIO=0.2"
set "MINDOS_IM_DINGTALK_STREAM_RECONNECT_MAX_ATTEMPTS=0"
set "MINDOS_IM_DINGTALK_OUTBOUND_ENABLED=true"
set "MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE=REPLACE_WITH_YOUR_ROBOT_CODE"

REM Slow-model experience preset (choose one)
REM chat (fast feel)
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_DELAY_MS=200"
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=已收到，正在处理，稍后给你完整回复。"
REM set "MINDOS_DISPATCHER_LLM_REPLY_MAX_CHARS=700"
REM set "MINDOS_DISPATCHER_PROMPT_MAX_CHARS=2000"
REM set "MINDOS_DISPATCHER_MEMORY_CONTEXT_MAX_CHARS=1200"

REM speed-first preset (recommended when model feels slow)
set "MINDOS_IM_DINGTALK_STREAM_WAITING_DELAY_MS=200"
set "MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=已收到，正在处理，稍后给你完整回复。"
set "MINDOS_DISPATCHER_LLM_REPLY_MAX_CHARS=700"
set "MINDOS_DISPATCHER_PROMPT_MAX_CHARS=2000"
set "MINDOS_DISPATCHER_MEMORY_CONTEXT_MAX_CHARS=1200"
set "MINDOS_DISPATCHER_MEMORY_CONTEXT_KEEP_RECENT_TURNS=1"

REM writing (quality)
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_DELAY_MS=500"
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=我正在准备更完整的回复，请再给我一点时间。"
REM set "MINDOS_DISPATCHER_LLM_REPLY_MAX_CHARS=1200"
REM set "MINDOS_DISPATCHER_PROMPT_MAX_CHARS=3000"
REM set "MINDOS_DISPATCHER_MEMORY_CONTEXT_MAX_CHARS=1800"
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
java -jar "%JAR_PATH%" --spring.profiles.active=%SPRING_PROFILE% --server.port=%SERVER_PORT%
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

1) Edit only secrets in mindos-secrets.properties (KEY=value, # for comment):
   - OPENROUTER/DOUBAO/QWEN keys live here
   - for DingTalk stream mode, fill MINDOS_IM_DINGTALK_STREAM_CLIENT_ID, MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET, and MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE
   - outbound app key/secret are optional; when blank, mindos-server.env.bat reuses the stream clientId/clientSecret
   - legacy MINDOS_IM_DINGTALK_APP_KEY / _APP_SECRET names are still accepted for compatibility

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
- PID file is stored in run\assistant-api.pid.
- Default Spring profile is solo.
- All launcher scripts auto-load mindos-server.env.bat (which itself loads mindos-secrets.properties if present).
- If startup logs show {"event":"dingtalk.stream.lifecycle.skipped","reason":"stream_mode_disabled"}, the stream credentials or enable flags were not populated.
- mindos-server.full.env.bat is a commented reference file only; it is NOT auto-loaded.
- Use mindos-server.full.env.bat for harness/canary examples, then copy selected lines back to mindos-server.env.bat if you need overrides.
- In provider maps, commas split entries and the first colon splits provider name from value.
- Doubao Ark Chat uses Authorization: Bearer <ARK_API_KEY> and requires a real Model ID or Endpoint ID.
- Avoid unescaped special characters in values: & | < > % ^ !
TXT

echo "[DONE] Windows bundle exported to: $OUTPUT_DIR"
echo "       Copy this whole directory to your Windows machine, then run mindos-server.bat"
