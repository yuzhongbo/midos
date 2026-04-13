#!/bin/bash
# Minimal config loader. Place secrets in mindos-secrets.properties (KEY=value, # or ; for comments).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRETS_FILE="${ROOT_DIR}/mindos-secrets.properties"

export MINDOS_ENV_LOADED=""
export MINDOS_ENV_STAGE="boot"

mindos_set_if_blank() {
  local name="$1"
  local value="$2"
  if [[ -z "${!name:-}" ]]; then
    export "$name=$value"
  fi
}

mindos_load_properties_file() {
  local file_path="$1"
  [[ -f "$file_path" ]] || return 0

  while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
    local line="${raw_line%$'\r'}"
    [[ -z "${line//[[:space:]]/}" ]] && continue
    [[ "$line" =~ ^[[:space:]]*[#\;] ]] && continue
    [[ "$line" != *=* ]] && continue

    local key="${line%%=*}"
    local value="${line#*=}"
    key="${key#"${key%%[![:space:]]*}"}"
    key="${key%"${key##*[![:space:]]}"}"
    [[ -z "$key" ]] && continue

    export "$key=$value"
  done < "$file_path"
}

mindos_join_csv_entries() {
  local result=""
  local entry
  for entry in "$@"; do
    [[ -z "$entry" ]] && continue
    if [[ -n "$result" ]]; then
      result+=",${entry}"
    else
      result="$entry"
    fi
  done
  printf '%s' "$result"
}

mindos_map_entry_if_value() {
  local key="$1"
  local value="${2:-}"
  [[ -z "$value" ]] && return 0
  printf '%s:%s' "$key" "$value"
}

mindos_canonical_model_preset() {
  local raw="${1:-}"
  [[ -z "$raw" ]] && return 0

  local upper
  upper="$(printf '%s' "$raw" | tr '[:lower:]-' '[:upper:]_')"
  case "$upper" in
    OPENROUTER|OPENROUTER_INTENT)
      printf 'OPENROUTER_INTENT'
      ;;
    QWEN|QWEN_STABLE)
      printf 'QWEN_STABLE'
      ;;
    DOUBAO|DOUBAO_STABLE)
      printf 'DOUBAO_STABLE'
      ;;
    LOCAL_QWEN|LOCALFIRST|LOCAL_FIRST|CUSTOM_LOCAL_FIRST)
      printf 'CUSTOM_LOCAL_FIRST'
      ;;
    CN|CN_DUAL)
      printf 'CN_DUAL'
      ;;
    OPENAI|OPENAI_NATIVE)
      printf 'OPENAI_NATIVE'
      ;;
    GEMINI|GEMINI_NATIVE)
      printf 'GEMINI_NATIVE'
      ;;
    GROK|GROK_NATIVE)
      printf 'GROK_NATIVE'
      ;;
    CUSTOM|MANUAL|NONE)
      printf 'CUSTOM'
      ;;
    *)
      printf '%s' "$raw"
      ;;
  esac
}

mindos_apply_model_preset() {
  local canonical
  canonical="$(mindos_canonical_model_preset "${MINDOS_MODEL_PRESET:-}")"
  if [[ -z "$canonical" && -n "${MINDOS_LLM_PROFILE:-}" ]]; then
    canonical="$(mindos_canonical_model_preset "${MINDOS_LLM_PROFILE}")"
  fi
  if [[ -z "$canonical" ]]; then
    canonical="OPENROUTER_INTENT"
  fi
  if [[ "$canonical" == "CUSTOM" ]]; then
    export MINDOS_MODEL_PRESET="CUSTOM"
    return 0
  fi

  export MINDOS_MODEL_PRESET="$canonical"
  export MINDOS_LLM_PROFILE="$canonical"

  mindos_set_if_blank MINDOS_LLM_ENDPOINT_OPENROUTER "https://openrouter.ai/api/v1/chat/completions"
  mindos_set_if_blank MINDOS_LLM_ENDPOINT_QWEN "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
  mindos_set_if_blank MINDOS_LLM_ENDPOINT_DOUBAO "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
  mindos_set_if_blank MINDOS_LLM_ENDPOINT_OPENAI "https://api.openai.com/v1/chat/completions"
  mindos_set_if_blank MINDOS_LLM_ENDPOINT_GEMINI "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
  mindos_set_if_blank MINDOS_LLM_ENDPOINT_GROK "https://api.x.ai/v1/chat/completions"
  mindos_set_if_blank MINDOS_LOCAL_LLM_ENDPOINT "http://localhost:11434/api/chat"
  mindos_set_if_blank MINDOS_LOCAL_LLM_MODEL "gemma3:1b-it-q4_K_M"
  mindos_set_if_blank MINDOS_QWEN_MODEL "qwen3.6-plus"
  mindos_set_if_blank MINDOS_OPENAI_MODEL "gpt-4.1"
  mindos_set_if_blank MINDOS_GEMINI_MODEL "gemini-2.5-pro"
  mindos_set_if_blank MINDOS_GROK_MODEL "grok-4"
  mindos_set_if_blank MINDOS_OPENROUTER_GPT_MODEL "openai/gpt-5.2"
  mindos_set_if_blank MINDOS_OPENROUTER_GROK_MODEL "x-ai/grok-4"
  mindos_set_if_blank MINDOS_OPENROUTER_GEMINI_MODEL "google/gemini-2.5-pro"

  case "$canonical" in
    OPENROUTER_INTENT)
      export MINDOS_LLM_MODE="OPENROUTER"
      export MINDOS_LLM_PROVIDER="gpt"
      export MINDOS_LLM_ROUTING_MODE="fixed"
      export MINDOS_LLM_ROUTING_STAGE_MAP="llm-dsl:gpt,llm-fallback:qwen"
      export MINDOS_LLM_ROUTING_PRESET_MAP="cost:gpt,balanced:gpt,quality:gemini"
      export MINDOS_LLM_PROVIDER_ENDPOINTS="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value gpt "${MINDOS_LLM_ENDPOINT_OPENROUTER}")" \
        "$(mindos_map_entry_if_value grok "${MINDOS_LLM_ENDPOINT_OPENROUTER}")" \
        "$(mindos_map_entry_if_value gemini "${MINDOS_LLM_ENDPOINT_OPENROUTER}")" \
        "$(mindos_map_entry_if_value qwen "${MINDOS_LLM_ENDPOINT_QWEN}")")"
      export MINDOS_LLM_PROVIDER_KEYS="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value gpt "${MINDOS_OPENROUTER_KEY:-}")" \
        "$(mindos_map_entry_if_value grok "${MINDOS_OPENROUTER_KEY:-}")" \
        "$(mindos_map_entry_if_value gemini "${MINDOS_OPENROUTER_KEY:-}")" \
        "$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_KEY:-}")")"
      export MINDOS_LLM_PROVIDER_MODELS="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value gpt "${MINDOS_OPENROUTER_GPT_MODEL}")" \
        "$(mindos_map_entry_if_value grok "${MINDOS_OPENROUTER_GROK_MODEL}")" \
        "$(mindos_map_entry_if_value gemini "${MINDOS_OPENROUTER_GEMINI_MODEL}")" \
        "$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_MODEL}")")"
      mindos_set_if_blank MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED "true"
      ;;
    QWEN_STABLE)
      export MINDOS_LLM_MODE="QWEN_NATIVE"
      export MINDOS_LLM_PROVIDER="qwen"
      export MINDOS_LLM_ROUTING_MODE="fixed"
      export MINDOS_LLM_ROUTING_STAGE_MAP="llm-dsl:qwen,llm-fallback:qwen"
      export MINDOS_LLM_ROUTING_PRESET_MAP="cost:qwen,balanced:qwen,quality:qwen"
      export MINDOS_LLM_PROVIDER_ENDPOINTS="qwen:${MINDOS_LLM_ENDPOINT_QWEN}"
      export MINDOS_LLM_PROVIDER_KEYS="$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_KEY:-}")"
      export MINDOS_LLM_PROVIDER_MODELS="qwen:${MINDOS_QWEN_MODEL}"
      mindos_set_if_blank MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED "false"
      ;;
    DOUBAO_STABLE)
      export MINDOS_LLM_MODE="DOUBAO_NATIVE"
      export MINDOS_LLM_PROVIDER="doubao"
      export MINDOS_LLM_ROUTING_MODE="fixed"
      export MINDOS_LLM_ROUTING_STAGE_MAP="llm-dsl:doubao,llm-fallback:doubao"
      export MINDOS_LLM_ROUTING_PRESET_MAP="cost:doubao,balanced:doubao,quality:doubao"
      export MINDOS_LLM_PROVIDER_ENDPOINTS="doubao:${MINDOS_LLM_ENDPOINT_DOUBAO}"
      export MINDOS_LLM_PROVIDER_KEYS="$(mindos_map_entry_if_value doubao "${MINDOS_DOUBAO_ARK_KEY:-}")"
      export MINDOS_LLM_PROVIDER_MODELS="$(mindos_map_entry_if_value doubao "${MINDOS_DOUBAO_ENDPOINT_ID:-}")"
      mindos_set_if_blank MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED "false"
      ;;
    CUSTOM_LOCAL_FIRST)
      export MINDOS_LLM_MODE="LOCAL_FIRST"
      export MINDOS_LLM_PROVIDER="local"
      export MINDOS_LLM_ROUTING_MODE="fixed"
      export MINDOS_LLM_ROUTING_STAGE_MAP="llm-dsl:local,llm-fallback:qwen"
      export MINDOS_LLM_ROUTING_PRESET_MAP="cost:local,balanced:local,quality:qwen"
      export MINDOS_LLM_PROVIDER_ENDPOINTS="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value local "${MINDOS_LOCAL_LLM_ENDPOINT}")" \
        "$(mindos_map_entry_if_value qwen "${MINDOS_LLM_ENDPOINT_QWEN}")")"
      export MINDOS_LLM_PROVIDER_KEYS="$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_KEY:-}")"
      export MINDOS_LLM_PROVIDER_MODELS="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value local "${MINDOS_LOCAL_LLM_MODEL}")" \
        "$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_MODEL}")")"
      mindos_set_if_blank MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED "false"
      mindos_set_if_blank MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_FORCE_LOCAL "true"
      mindos_set_if_blank MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_LLM_PROVIDER "local"
      mindos_set_if_blank MINDOS_DISPATCHER_LLM_DSL_PROVIDER "local"
      mindos_set_if_blank MINDOS_DISPATCHER_LLM_FALLBACK_PROVIDER "qwen"
      mindos_set_if_blank MINDOS_DISPATCHER_SKILL_FINALIZE_WITH_LLM_PROVIDER "qwen"
      ;;
    CN_DUAL)
      export MINDOS_LLM_MODE="CN_DUAL_NATIVE"
      export MINDOS_LLM_PROVIDER="qwen"
      export MINDOS_LLM_ROUTING_MODE="fixed"
      export MINDOS_LLM_ROUTING_STAGE_MAP="llm-dsl:qwen,llm-fallback:doubao"
      export MINDOS_LLM_ROUTING_PRESET_MAP="cost:qwen,balanced:qwen,quality:doubao"
      export MINDOS_LLM_PROVIDER_ENDPOINTS="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value qwen "${MINDOS_LLM_ENDPOINT_QWEN}")" \
        "$(mindos_map_entry_if_value doubao "${MINDOS_LLM_ENDPOINT_DOUBAO}")")"
      export MINDOS_LLM_PROVIDER_KEYS="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_KEY:-}")" \
        "$(mindos_map_entry_if_value doubao "${MINDOS_DOUBAO_ARK_KEY:-}")")"
      export MINDOS_LLM_PROVIDER_MODELS="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_MODEL}")" \
        "$(mindos_map_entry_if_value doubao "${MINDOS_DOUBAO_ENDPOINT_ID:-}")")"
      mindos_set_if_blank MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED "false"
      ;;
    OPENAI_NATIVE)
      export MINDOS_LLM_MODE="OPENAI_NATIVE"
      export MINDOS_LLM_PROVIDER="openai"
      export MINDOS_LLM_ROUTING_MODE="fixed"
      export MINDOS_LLM_ROUTING_STAGE_MAP="llm-dsl:openai,llm-fallback:openai"
      export MINDOS_LLM_ROUTING_PRESET_MAP="cost:openai,balanced:openai,quality:openai"
      export MINDOS_LLM_PROVIDER_ENDPOINTS="openai:${MINDOS_LLM_ENDPOINT_OPENAI}"
      export MINDOS_LLM_PROVIDER_KEYS="$(mindos_map_entry_if_value openai "${MINDOS_OPENAI_KEY:-}")"
      export MINDOS_LLM_PROVIDER_MODELS="openai:${MINDOS_OPENAI_MODEL}"
      mindos_set_if_blank MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED "false"
      ;;
    GEMINI_NATIVE)
      export MINDOS_LLM_MODE="GEMINI_NATIVE"
      export MINDOS_LLM_PROVIDER="gemini"
      export MINDOS_LLM_ROUTING_MODE="fixed"
      export MINDOS_LLM_ROUTING_STAGE_MAP="llm-dsl:gemini,llm-fallback:gemini"
      export MINDOS_LLM_ROUTING_PRESET_MAP="cost:gemini,balanced:gemini,quality:gemini"
      export MINDOS_LLM_PROVIDER_ENDPOINTS="gemini:${MINDOS_LLM_ENDPOINT_GEMINI}"
      export MINDOS_LLM_PROVIDER_KEYS="$(mindos_map_entry_if_value gemini "${MINDOS_GEMINI_KEY:-}")"
      export MINDOS_LLM_PROVIDER_MODELS="gemini:${MINDOS_GEMINI_MODEL}"
      mindos_set_if_blank MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED "false"
      ;;
    GROK_NATIVE)
      export MINDOS_LLM_MODE="GROK_NATIVE"
      export MINDOS_LLM_PROVIDER="grok"
      export MINDOS_LLM_ROUTING_MODE="fixed"
      export MINDOS_LLM_ROUTING_STAGE_MAP="llm-dsl:grok,llm-fallback:grok"
      export MINDOS_LLM_ROUTING_PRESET_MAP="cost:grok,balanced:grok,quality:grok"
      export MINDOS_LLM_PROVIDER_ENDPOINTS="grok:${MINDOS_LLM_ENDPOINT_GROK}"
      export MINDOS_LLM_PROVIDER_KEYS="$(mindos_map_entry_if_value grok "${MINDOS_GROK_KEY:-}")"
      export MINDOS_LLM_PROVIDER_MODELS="grok:${MINDOS_GROK_MODEL}"
      mindos_set_if_blank MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED "false"
      ;;
  esac
}

export MINDOS_ENV_STAGE="loading_secrets"
mindos_load_properties_file "$SECRETS_FILE"

export MINDOS_ENV_STAGE="defaults"
mindos_set_if_blank MINDOS_SPRING_PROFILE "solo"
mindos_set_if_blank MINDOS_SERVER_PORT "8080"
mindos_set_if_blank MINDOS_MEMORY_FILE_REPO_ENABLED "true"
mindos_set_if_blank MINDOS_MEMORY_FILE_REPO_BASE_DIR "data/memory-sync"
mindos_set_if_blank MINDOS_PAUSE_ON_EXIT "true"
mindos_set_if_blank MINDOS_MODEL_PRESET "${MINDOS_LLM_PROFILE:-OPENROUTER_INTENT}"
mindos_set_if_blank MINDOS_DISPATCHER_SKILL_PRE_ANALYZE_MODE "never"

mindos_set_if_blank MINDOS_IM_ENABLED "true"
mindos_set_if_blank MINDOS_IM_DINGTALK_ENABLED "true"
mindos_set_if_blank MINDOS_IM_DINGTALK_VERIFY_SIGNATURE "false"
mindos_set_if_blank MINDOS_IM_DINGTALK_REPLY_TIMEOUT_MS "2500"
mindos_set_if_blank MINDOS_IM_DINGTALK_REPLY_MAX_CHARS "1200"
mindos_set_if_blank MINDOS_IM_DINGTALK_STREAM_TOPIC "/v1.0/im/bot/messages/get"
mindos_set_if_blank MINDOS_IM_DINGTALK_STREAM_WAITING_DELAY_MS "1200"
mindos_set_if_blank MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT "处理中，请稍候。"
mindos_set_if_blank MINDOS_IM_DINGTALK_STREAM_FORCE_WAITING "false"
mindos_set_if_blank MINDOS_IM_DINGTALK_MESSAGE_CARD_ENABLED "true"
mindos_set_if_blank MINDOS_IM_DINGTALK_MESSAGE_UPDATE_ENABLED "true"
mindos_set_if_blank MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_INTERVAL_MS "250"
mindos_set_if_blank MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_DELTA_CHARS "24"
mindos_set_if_blank MINDOS_IM_DINGTALK_AGENT_STATUS_ENABLED "true"
mindos_set_if_blank MINDOS_IM_DINGTALK_TOKEN_MONITOR_ENABLED "true"
mindos_set_if_blank MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY ""
mindos_set_if_blank MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET ""
mindos_set_if_blank MINDOS_IM_DINGTALK_OUTBOUND_SEND_URL "https://api.dingtalk.com/v1.0/im/chat/messages/send"
mindos_set_if_blank MINDOS_IM_DINGTALK_OUTBOUND_UPDATE_URL "https://api.dingtalk.com/v1.0/im/chat/messages/update"

if [[ -n "${MINDOS_IM_DINGTALK_APP_KEY:-}" && -z "${MINDOS_IM_DINGTALK_STREAM_CLIENT_ID:-}" ]]; then
  export MINDOS_IM_DINGTALK_STREAM_CLIENT_ID="$MINDOS_IM_DINGTALK_APP_KEY"
fi
if [[ -n "${MINDOS_IM_DINGTALK_APP_SECRET:-}" && -z "${MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET:-}" ]]; then
  export MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET="$MINDOS_IM_DINGTALK_APP_SECRET"
fi
if [[ -n "${MINDOS_IM_DINGTALK_APP_KEY:-}" && -z "${MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY:-}" ]]; then
  export MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY="$MINDOS_IM_DINGTALK_APP_KEY"
fi
if [[ -n "${MINDOS_IM_DINGTALK_APP_SECRET:-}" && -z "${MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET:-}" ]]; then
  export MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET="$MINDOS_IM_DINGTALK_APP_SECRET"
fi

if [[ -z "${MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY:-}" ]]; then
  export MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY="${MINDOS_IM_DINGTALK_STREAM_CLIENT_ID:-}"
fi
if [[ -z "${MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET:-}" ]]; then
  export MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET="${MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET:-}"
fi

if [[ -z "${MINDOS_IM_DINGTALK_STREAM_ENABLED:-}" ]]; then
  if [[ -n "${MINDOS_IM_DINGTALK_STREAM_CLIENT_ID:-}" && -n "${MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET:-}" ]]; then
    export MINDOS_IM_DINGTALK_STREAM_ENABLED="true"
  else
    export MINDOS_IM_DINGTALK_STREAM_ENABLED="false"
  fi
fi

if [[ -z "${MINDOS_IM_DINGTALK_OUTBOUND_ENABLED:-}" ]]; then
  if [[ -n "${MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE:-}" && -n "${MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY:-}" && -n "${MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET:-}" ]]; then
    export MINDOS_IM_DINGTALK_OUTBOUND_ENABLED="true"
  else
    export MINDOS_IM_DINGTALK_OUTBOUND_ENABLED="false"
  fi
fi

mindos_apply_model_preset

export MINDOS_ENV_STAGE="done"
export MINDOS_ENV_LOADED="1"
