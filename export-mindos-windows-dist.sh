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
REM Keep the set "KEY=value" format unchanged.
REM Only edit the text to the right of the first '='.
REM Avoid special CMD symbols in values unless you fully understand escaping rules.

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
set "MINDOS_LLM_PROVIDER_ENDPOINTS=gpt:https://openrouter.ai/api/v1/chat/completions,grok:https://openrouter.ai/api/v1/chat/completions,gemini:https://openrouter.ai/api/v1/chat/completions"
set "MINDOS_LLM_PROVIDER_KEYS=gpt:%MINDOS_OPENROUTER_KEY%,grok:%MINDOS_OPENROUTER_KEY%,gemini:%MINDOS_OPENROUTER_KEY%,doubao:%MINDOS_DOUBAO_ARK_KEY%,qwen:%MINDOS_QWEN_KEY%"
set "MINDOS_LLM_PROVIDER_MODELS=gpt:openai/gpt-5.2,grok:x-ai/grok-4,gemini:google/gemini-2.5-pro,doubao:%MINDOS_DOUBAO_ENDPOINT_ID%,qwen:%MINDOS_QWEN_MODEL%"

REM ===== Harness metadata (experiment tracking) =====
set "MINDOS_HARNESS_STRATEGY_ID=or-intent-v1"
set "MINDOS_HARNESS_EXPERIMENT_ID=exp-20260330"
set "MINDOS_HARNESS_BASELINE_ID=baseline-fixed-gpt"

REM ===== Harness observability knobs =====
set "MINDOS_DISPATCHER_ROUTING_REPLAY_MAX_SAMPLES=500"
set "MINDOS_LLM_METRICS_CACHE_WINDOW_LOW_SAMPLE_THRESHOLD=30"

REM ===== Harness manual canary toggles =====
REM A (default): intent-routing strategy
REM set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=true"
REM B (baseline): fixed gpt only
REM set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=false"
REM set "MINDOS_LLM_PROVIDER=gpt"
REM C (fallback): doubao single provider
REM set "MINDOS_LLM_PROFILE=DOUBAO_STABLE"
set "MINDOS_LLM_RETRY_MAX_ATTEMPTS=1"
set "MINDOS_LLM_RETRY_DELAY_MS=0"

REM Rollback toggles (uncomment one line if needed)
REM set "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED=false"
REM set "MINDOS_LLM_PROVIDER=gpt"
REM set "MINDOS_LLM_PROFILE=QWEN_STABLE"

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
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=Got it. Processing now, full reply is coming soon."
REM set "MINDOS_DISPATCHER_LLM_REPLY_MAX_CHARS=700"
REM set "MINDOS_DISPATCHER_PROMPT_MAX_CHARS=2000"
REM set "MINDOS_DISPATCHER_MEMORY_CONTEXT_MAX_CHARS=1200"

REM speed-first preset (recommended when model feels slow)
set "MINDOS_IM_DINGTALK_STREAM_WAITING_DELAY_MS=200"
set "MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=Got it. Processing now, full reply is coming soon."
set "MINDOS_DISPATCHER_LLM_REPLY_MAX_CHARS=700"
set "MINDOS_DISPATCHER_PROMPT_MAX_CHARS=2000"
set "MINDOS_DISPATCHER_MEMORY_CONTEXT_MAX_CHARS=1200"
set "MINDOS_DISPATCHER_MEMORY_CONTEXT_KEEP_RECENT_TURNS=1"

REM writing (quality)
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_DELAY_MS=500"
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=I am preparing a more complete answer, please give me a bit more time."
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
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=Got it. Processing now, full reply is coming soon."
REM set "MINDOS_DISPATCHER_LLM_REPLY_MAX_CHARS=700"
REM set "MINDOS_DISPATCHER_PROMPT_MAX_CHARS=2000"
REM set "MINDOS_DISPATCHER_MEMORY_CONTEXT_MAX_CHARS=1200"

REM speed-first preset (recommended when model feels slow)
set "MINDOS_IM_DINGTALK_STREAM_WAITING_DELAY_MS=200"
set "MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=Got it. Processing now, full reply is coming soon."
set "MINDOS_DISPATCHER_LLM_REPLY_MAX_CHARS=700"
set "MINDOS_DISPATCHER_PROMPT_MAX_CHARS=2000"
set "MINDOS_DISPATCHER_MEMORY_CONTEXT_MAX_CHARS=1200"
set "MINDOS_DISPATCHER_MEMORY_CONTEXT_KEEP_RECENT_TURNS=1"

REM writing (quality)
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_DELAY_MS=500"
REM set "MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT=I am preparing a more complete answer, please give me a bit more time."
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
if exist "%ROOT_DIR%mindos-server.env.bat" (
  set "MINDOS_ENV_LOADED="
  call "%ROOT_DIR%mindos-server.env.bat"
  if errorlevel 1 (
    echo [FAIL] mindos-server.env.bat exited with code %ERRORLEVEL%.
    goto :pause_and_fail
  )
  if not "%MINDOS_ENV_LOADED%"=="1" (
    echo [FAIL] mindos-server.env.bat did not finish loading. Check for broken quotes/encoding in the file.
    goto :pause_and_fail
  )
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
if exist "%ROOT_DIR%mindos-server.env.bat" (
  echo [INFO] Loading config from mindos-server.env.bat...
  set "MINDOS_ENV_LOADED="
  call "%ROOT_DIR%mindos-server.env.bat"
  set "ENV_EXIT_CODE=%ERRORLEVEL%"
  if "%ENV_EXIT_CODE%"=="" set "ENV_EXIT_CODE=1"
  if not "%ENV_EXIT_CODE%"=="0" (
    echo [FAIL] mindos-server.env.bat exited with code %ENV_EXIT_CODE%.
    exit /b %ENV_EXIT_CODE%
  )
  if not "%MINDOS_ENV_LOADED%"=="1" (
    echo [FAIL] mindos-server.env.bat did not finish loading. Check for broken quotes/encoding in the file.
    exit /b 1
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

1) Edit mindos-server.env.bat before startup:
   - keep each line in the form: set "KEY=value"
   - edit only the text to the right of the first '='
   - start from the safe defaults in mindos-server.env.bat
   - choose mode first: MINDOS_LLM_MODE=OPENROUTER (default) or MINDOS_LLM_MODE=DOUBAO
   - OPENROUTER mode uses intent routing (coding->gpt, realtime/news->grok, emotional->gemini)
   - if you use Doubao Ark, set MINDOS_LLM_PROVIDER_MODELS=doubao:<Model ID or Endpoint ID>
   - MINDOS_IM_DINGTALK_* / MINDOS_IM_WECHAT_* stay disabled until you have real bot credentials
   - MINDOS_SERVER_PORT for local port

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
- All launcher scripts auto-load mindos-server.env.bat from the same directory.
- mindos-server.full.env.bat is a commented reference file only; it is NOT auto-loaded.
- use mindos-server.full.env.bat for harness/canary examples, then copy selected lines back to mindos-server.env.bat.
- In provider maps, commas split entries and the first colon splits provider name from value.
- Doubao Ark Chat uses Authorization: Bearer <ARK_API_KEY> and requires a real Model ID or Endpoint ID.
- Avoid unescaped special characters in values: & | < > % ^ !
TXT

echo "[DONE] Windows bundle exported to: $OUTPUT_DIR"
echo "       Copy this whole directory to your Windows machine, then run mindos-server.bat"

