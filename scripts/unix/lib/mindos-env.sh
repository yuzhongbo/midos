#!/usr/bin/env bash
set -euo pipefail

mindos_load_properties_file() {
  local file_path="$1"
  if [[ ! -f "$file_path" ]]; then
    return 0
  fi

  while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
    local line="${raw_line%$'\r'}"
    [[ -z "${line//[[:space:]]/}" ]] && continue
    [[ "$line" =~ ^[[:space:]]*[#\;] ]] && continue

    if [[ "$line" != *=* ]]; then
      echo "[WARN] Skip invalid line (missing '='): $line"
      continue
    fi

    local key="${line%%=*}"
    local value="${line#*=}"
    key="${key#"${key%%[![:space:]]*}"}"
    key="${key%"${key##*[![:space:]]}"}"

    if [[ ! "$key" =~ ^[A-Za-z_][A-Za-z0-9_.-]*$ ]]; then
      echo "[WARN] Skip invalid key: $key"
      continue
    fi

    export "$key=$value"
  done < "$file_path"
}

mindos_is_placeholder() {
  local value="${1:-}"
  local upper
  upper="$(printf '%s' "$value" | tr '[:lower:]' '[:upper:]')"
  [[ "$upper" == REPLACE_WITH_* || "$upper" == YOUR_* ]]
}

mindos_count_csv_entries() {
  local raw="${1:-}"
  if [[ -z "$raw" ]]; then
    echo "0"
    return 0
  fi
  local count=0
  local item
  IFS=',' read -ra items <<< "$raw"
  for item in "${items[@]}"; do
    local trimmed="$item"
    trimmed="${trimmed#"${trimmed%%[![:space:]]*}"}"
    trimmed="${trimmed%"${trimmed##*[![:space:]]}"}"
    [[ -z "$trimmed" ]] && continue
    count=$((count + 1))
  done
  echo "$count"
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

mindos_apply_env_value() {
  local name="$1"
  local value="$2"
  local force="${3:-false}"
  if [[ "$force" != "true" && -n "${!name:-}" ]]; then
    return 0
  fi
  export "$name=$value"
}

mindos_apply_model_preset() {
  local explicit_preset="${MINDOS_MODEL_PRESET:-}"
  local canonical=""
  local force_core="false"

  if [[ -n "$explicit_preset" ]]; then
    canonical="$(mindos_canonical_model_preset "$explicit_preset")"
    force_core="true"
  elif [[ -n "${MINDOS_LLM_PROFILE:-}" ]]; then
    canonical="$(mindos_canonical_model_preset "${MINDOS_LLM_PROFILE}")"
  else
    return 0
  fi

  if [[ -z "$canonical" || "$canonical" == "CUSTOM" ]]; then
    if [[ -n "$explicit_preset" ]]; then
      export "MINDOS_MODEL_PRESET=${explicit_preset}"
    fi
    return 0
  fi

  export "MINDOS_MODEL_PRESET=${canonical}"
  export "MINDOS_LLM_PROFILE=${canonical}"

  : "${MINDOS_LLM_ENDPOINT_OPENROUTER:=https://openrouter.ai/api/v1/chat/completions}"
  : "${MINDOS_LLM_ENDPOINT_QWEN:=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}"
  : "${MINDOS_LLM_ENDPOINT_DOUBAO:=https://ark.cn-beijing.volces.com/api/v3/chat/completions}"
  : "${MINDOS_LLM_ENDPOINT_OPENAI:=https://api.openai.com/v1/chat/completions}"
  : "${MINDOS_LLM_ENDPOINT_GEMINI:=https://generativelanguage.googleapis.com/v1beta/openai/chat/completions}"
  : "${MINDOS_LLM_ENDPOINT_GROK:=https://api.x.ai/v1/chat/completions}"
  : "${MINDOS_LOCAL_LLM_ENDPOINT:=http://localhost:11434/api/chat}"
  : "${MINDOS_LOCAL_LLM_MODEL:=gemma3:1b-it-q4_K_M}"
  : "${MINDOS_QWEN_MODEL:=qwen3.6-plus}"
  : "${MINDOS_OPENAI_MODEL:=gpt-4.1}"
  : "${MINDOS_GEMINI_MODEL:=gemini-2.5-pro}"
  : "${MINDOS_GROK_MODEL:=grok-4}"
  : "${MINDOS_OPENROUTER_GPT_MODEL:=openai/gpt-5.2}"
  : "${MINDOS_OPENROUTER_GROK_MODEL:=x-ai/grok-4}"
  : "${MINDOS_OPENROUTER_GEMINI_MODEL:=google/gemini-2.5-pro}"

  local provider=""
  local llm_mode=""
  local routing_mode="fixed"
  local stage_map=""
  local preset_map=""
  local endpoints=""
  local keys=""
  local models=""
  local intent_routing_default=""

  case "$canonical" in
    OPENROUTER_INTENT)
      provider="gpt"
      llm_mode="OPENROUTER"
      stage_map="llm-dsl:gpt,llm-fallback:qwen"
      preset_map="cost:gpt,balanced:gpt,quality:gemini"
      endpoints="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value gpt "${MINDOS_LLM_ENDPOINT_OPENROUTER}")" \
        "$(mindos_map_entry_if_value grok "${MINDOS_LLM_ENDPOINT_OPENROUTER}")" \
        "$(mindos_map_entry_if_value gemini "${MINDOS_LLM_ENDPOINT_OPENROUTER}")" \
        "$(mindos_map_entry_if_value qwen "${MINDOS_LLM_ENDPOINT_QWEN}")")"
      keys="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value gpt "${MINDOS_OPENROUTER_KEY:-}")" \
        "$(mindos_map_entry_if_value grok "${MINDOS_OPENROUTER_KEY:-}")" \
        "$(mindos_map_entry_if_value gemini "${MINDOS_OPENROUTER_KEY:-}")" \
        "$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_KEY:-}")")"
      models="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value gpt "${MINDOS_OPENROUTER_GPT_MODEL}")" \
        "$(mindos_map_entry_if_value grok "${MINDOS_OPENROUTER_GROK_MODEL}")" \
        "$(mindos_map_entry_if_value gemini "${MINDOS_OPENROUTER_GEMINI_MODEL}")" \
        "$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_MODEL}")")"
      intent_routing_default="true"
      ;;
    QWEN_STABLE)
      provider="qwen"
      llm_mode="QWEN_NATIVE"
      stage_map="llm-dsl:qwen,llm-fallback:qwen"
      preset_map="cost:qwen,balanced:qwen,quality:qwen"
      endpoints="$(mindos_map_entry_if_value qwen "${MINDOS_LLM_ENDPOINT_QWEN}")"
      keys="$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_KEY:-}")"
      models="$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_MODEL}")"
      intent_routing_default="false"
      ;;
    DOUBAO_STABLE)
      provider="doubao"
      llm_mode="DOUBAO_NATIVE"
      stage_map="llm-dsl:doubao,llm-fallback:doubao"
      preset_map="cost:doubao,balanced:doubao,quality:doubao"
      endpoints="$(mindos_map_entry_if_value doubao "${MINDOS_LLM_ENDPOINT_DOUBAO}")"
      keys="$(mindos_map_entry_if_value doubao "${MINDOS_DOUBAO_ARK_KEY:-}")"
      models="$(mindos_map_entry_if_value doubao "${MINDOS_DOUBAO_ENDPOINT_ID:-}")"
      intent_routing_default="false"
      ;;
    CUSTOM_LOCAL_FIRST)
      provider="local"
      llm_mode="LOCAL_FIRST"
      stage_map="llm-dsl:local,llm-fallback:qwen"
      preset_map="cost:local,balanced:local,quality:qwen"
      endpoints="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value local "${MINDOS_LOCAL_LLM_ENDPOINT}")" \
        "$(mindos_map_entry_if_value qwen "${MINDOS_LLM_ENDPOINT_QWEN}")")"
      keys="$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_KEY:-}")"
      models="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value local "${MINDOS_LOCAL_LLM_MODEL}")" \
        "$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_MODEL}")")"
      intent_routing_default="false"
      mindos_apply_env_value "MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_FORCE_LOCAL" "true" "false"
      mindos_apply_env_value "MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_LLM_PROVIDER" "local" "false"
      mindos_apply_env_value "MINDOS_DISPATCHER_LLM_DSL_PROVIDER" "local" "false"
      mindos_apply_env_value "MINDOS_DISPATCHER_LLM_FALLBACK_PROVIDER" "qwen" "false"
      mindos_apply_env_value "MINDOS_DISPATCHER_SKILL_FINALIZE_WITH_LLM_PROVIDER" "qwen" "false"
      ;;
    CN_DUAL)
      provider="qwen"
      llm_mode="CN_DUAL_NATIVE"
      stage_map="llm-dsl:qwen,llm-fallback:doubao"
      preset_map="cost:qwen,balanced:qwen,quality:doubao"
      endpoints="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value qwen "${MINDOS_LLM_ENDPOINT_QWEN}")" \
        "$(mindos_map_entry_if_value doubao "${MINDOS_LLM_ENDPOINT_DOUBAO}")")"
      keys="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_KEY:-}")" \
        "$(mindos_map_entry_if_value doubao "${MINDOS_DOUBAO_ARK_KEY:-}")")"
      models="$(mindos_join_csv_entries \
        "$(mindos_map_entry_if_value qwen "${MINDOS_QWEN_MODEL}")" \
        "$(mindos_map_entry_if_value doubao "${MINDOS_DOUBAO_ENDPOINT_ID:-}")")"
      intent_routing_default="false"
      ;;
    OPENAI_NATIVE)
      provider="openai"
      llm_mode="OPENAI_NATIVE"
      stage_map="llm-dsl:openai,llm-fallback:openai"
      preset_map="cost:openai,balanced:openai,quality:openai"
      endpoints="$(mindos_map_entry_if_value openai "${MINDOS_LLM_ENDPOINT_OPENAI}")"
      keys="$(mindos_map_entry_if_value openai "${MINDOS_OPENAI_KEY:-}")"
      models="$(mindos_map_entry_if_value openai "${MINDOS_OPENAI_MODEL}")"
      intent_routing_default="false"
      ;;
    GEMINI_NATIVE)
      provider="gemini"
      llm_mode="GEMINI_NATIVE"
      stage_map="llm-dsl:gemini,llm-fallback:gemini"
      preset_map="cost:gemini,balanced:gemini,quality:gemini"
      endpoints="$(mindos_map_entry_if_value gemini "${MINDOS_LLM_ENDPOINT_GEMINI}")"
      keys="$(mindos_map_entry_if_value gemini "${MINDOS_GEMINI_KEY:-}")"
      models="$(mindos_map_entry_if_value gemini "${MINDOS_GEMINI_MODEL}")"
      intent_routing_default="false"
      ;;
    GROK_NATIVE)
      provider="grok"
      llm_mode="GROK_NATIVE"
      stage_map="llm-dsl:grok,llm-fallback:grok"
      preset_map="cost:grok,balanced:grok,quality:grok"
      endpoints="$(mindos_map_entry_if_value grok "${MINDOS_LLM_ENDPOINT_GROK}")"
      keys="$(mindos_map_entry_if_value grok "${MINDOS_GROK_KEY:-}")"
      models="$(mindos_map_entry_if_value grok "${MINDOS_GROK_MODEL}")"
      intent_routing_default="false"
      ;;
    *)
      return 0
      ;;
  esac

  mindos_apply_env_value "MINDOS_LLM_MODE" "$llm_mode" "$force_core"
  mindos_apply_env_value "MINDOS_LLM_PROVIDER" "$provider" "$force_core"
  mindos_apply_env_value "MINDOS_LLM_ROUTING_MODE" "$routing_mode" "$force_core"
  mindos_apply_env_value "MINDOS_LLM_ROUTING_STAGE_MAP" "$stage_map" "$force_core"
  mindos_apply_env_value "MINDOS_LLM_ROUTING_PRESET_MAP" "$preset_map" "$force_core"
  mindos_apply_env_value "MINDOS_LLM_PROVIDER_ENDPOINTS" "$endpoints" "$force_core"
  mindos_apply_env_value "MINDOS_LLM_PROVIDER_KEYS" "$keys" "$force_core"
  mindos_apply_env_value "MINDOS_LLM_PROVIDER_MODELS" "$models" "$force_core"
  if [[ -n "$intent_routing_default" ]]; then
    mindos_apply_env_value "MINDOS_DISPATCHER_INTENT_ROUTING_ENABLED" "$intent_routing_default" "false"
  fi
}

mindos_effective_news_search_sources() {
  if [[ -n "${MINDOS_SKILL_NEWS_SEARCH_SEARCH_SOURCES:-}" ]]; then
    printf '%s' "${MINDOS_SKILL_NEWS_SEARCH_SEARCH_SOURCES}"
    return 0
  fi
  printf '%s' "${MINDOS_SKILLS_SEARCH_SOURCES:-}"
}

mindos_has_legacy_news_search_serper_config() {
  [[ -n "${MINDOS_SKILL_NEWS_SEARCH_SERPER_ENABLED:-}" \
    || -n "${MINDOS_SKILL_NEWS_SEARCH_SERPER_NEWS_URL:-}" \
    || -n "${MINDOS_SKILL_NEWS_SEARCH_SERPER_SEARCH_URL:-}" \
    || -n "${MINDOS_SKILL_NEWS_SEARCH_SERPER_API_KEY:-}" ]]
}

mindos_collect_placeholder_warnings() {
  local warnings=()

  if mindos_is_placeholder "${MINDOS_QWEN_KEY:-}"; then
    warnings+=("MINDOS_QWEN_KEY is still a placeholder.")
  fi

  if [[ "${MINDOS_LLM_PROVIDER_KEYS:-}" == *REPLACE_WITH_* ]]; then
    warnings+=("MINDOS_LLM_PROVIDER_KEYS contains placeholder values.")
  fi

  if [[ "${MINDOS_SKILLS_MCP_SERVERS:-}" == *REPLACE_WITH_* || "${MINDOS_SKILLS_MCP_SERVER_HEADERS:-}" == *REPLACE_WITH_* ]]; then
    warnings+=("MCP server config contains placeholder values.")
  fi

  if [[ "${MINDOS_SKILLS_SEARCH_SOURCES:-}" == *REPLACE_WITH_* || "${MINDOS_SKILL_NEWS_SEARCH_SEARCH_SOURCES:-}" == *REPLACE_WITH_* ]]; then
    warnings+=("Unified search source config contains placeholder values.")
  fi

  if [[ -n "${warnings[*]-}" ]]; then
    printf '%s\n' "${warnings[@]}"
  fi
}

mindos_print_or_fail_placeholder_warnings() {
  local strict_mode="$1"
  local warning
  local count=0

  while IFS= read -r warning; do
    [[ -z "$warning" ]] && continue
    echo "[WARN] $warning"
    count=$((count + 1))
  done < <(mindos_collect_placeholder_warnings)

  if (( count == 0 )); then
    return 0
  fi

  if [[ "$strict_mode" == "true" ]]; then
    echo "[ERROR] Placeholder values detected in strict mode. Fix secrets before startup."
    return 1
  fi

  echo "[WARN] Placeholder values detected. Local model can still run, but cloud/MCP features may fail."
  return 0
}

mindos_validate_kv_map_format() {
  local map_name="$1"
  local map_value="${2:-}"
  [[ -z "$map_value" ]] && return 0

  local entry
  IFS=',' read -ra entries <<< "$map_value"
  for entry in "${entries[@]}"; do
    local trimmed="$entry"
    trimmed="${trimmed#"${trimmed%%[![:space:]]*}"}"
    trimmed="${trimmed%"${trimmed##*[![:space:]]}"}"
    [[ -z "$trimmed" ]] && continue

    if [[ "$trimmed" != *:* ]]; then
      echo "[ERROR] Invalid $map_name entry (missing ':'): $trimmed"
      return 1
    fi

    local key="${trimmed%%:*}"
    local value="${trimmed#*:}"
    key="${key#"${key%%[![:space:]]*}"}"
    key="${key%"${key##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"

    if [[ -z "$value" ]]; then
      echo "[ERROR] Invalid $map_name entry (empty value): $trimmed"
      return 1
    fi
    if [[ ! "$key" =~ ^[A-Za-z0-9_.-]+$ ]]; then
      echo "[ERROR] Invalid $map_name key: $key"
      return 1
    fi
  done

  return 0
}

mindos_validate_runtime_maps() {
  mindos_validate_kv_map_format "MINDOS_LLM_PROVIDER_ENDPOINTS" "${MINDOS_LLM_PROVIDER_ENDPOINTS:-}" || return 1
  mindos_validate_kv_map_format "MINDOS_LLM_PROVIDER_KEYS" "${MINDOS_LLM_PROVIDER_KEYS:-}" || return 1
  mindos_validate_kv_map_format "MINDOS_LLM_PROVIDER_MODELS" "${MINDOS_LLM_PROVIDER_MODELS:-}" || return 1
  mindos_validate_kv_map_format "MINDOS_LLM_ROUTING_STAGE_MAP" "${MINDOS_LLM_ROUTING_STAGE_MAP:-}" || return 1
  mindos_validate_kv_map_format "MINDOS_LLM_ROUTING_PRESET_MAP" "${MINDOS_LLM_ROUTING_PRESET_MAP:-}" || return 1
  mindos_validate_kv_map_format "MINDOS_SKILLS_MCP_SERVERS" "${MINDOS_SKILLS_MCP_SERVERS:-}" || return 1
  mindos_validate_kv_map_format "MINDOS_SKILLS_MCP_SERVER_HEADERS" "${MINDOS_SKILLS_MCP_SERVER_HEADERS:-}" || return 1
  mindos_validate_kv_map_format "MINDOS_SKILLS_SEARCH_SOURCES" "${MINDOS_SKILLS_SEARCH_SOURCES:-}" || return 1
  mindos_validate_kv_map_format "MINDOS_SKILL_NEWS_SEARCH_SEARCH_SOURCES" "${MINDOS_SKILL_NEWS_SEARCH_SEARCH_SOURCES:-}" || return 1
  return 0
}

mindos_collect_drift_warnings() {
  local warnings=()
  local profile="${MINDOS_LLM_PROFILE:-}"
  local provider="${MINDOS_LLM_PROVIDER:-}"

  if [[ "$profile" == "QWEN_STABLE" && "$provider" != "qwen" ]]; then
    warnings+=("Profile QWEN_STABLE usually expects MINDOS_LLM_PROVIDER=qwen (current=$provider).")
  fi
  if [[ "$profile" == "DOUBAO_STABLE" && "$provider" != "doubao" ]]; then
    warnings+=("Profile DOUBAO_STABLE usually expects MINDOS_LLM_PROVIDER=doubao (current=$provider).")
  fi
  if [[ "$profile" == "CUSTOM_LOCAL_FIRST" && "$provider" != "local" ]]; then
    warnings+=("Profile CUSTOM_LOCAL_FIRST usually expects MINDOS_LLM_PROVIDER=local (current=$provider).")
  fi
  if [[ "${MINDOS_MODEL_PRESET:-}" == "OPENROUTER_INTENT" && -z "${MINDOS_OPENROUTER_KEY:-}" ]]; then
    warnings+=("Model preset OPENROUTER_INTENT is selected but MINDOS_OPENROUTER_KEY is blank.")
  fi
  if [[ "${MINDOS_MODEL_PRESET:-}" == "QWEN_STABLE" && -z "${MINDOS_QWEN_KEY:-}" ]]; then
    warnings+=("Model preset QWEN_STABLE is selected but MINDOS_QWEN_KEY is blank.")
  fi
  if [[ "${MINDOS_MODEL_PRESET:-}" == "DOUBAO_STABLE" && -z "${MINDOS_DOUBAO_ARK_KEY:-}" ]]; then
    warnings+=("Model preset DOUBAO_STABLE is selected but MINDOS_DOUBAO_ARK_KEY is blank.")
  fi
  if [[ "${MINDOS_MODEL_PRESET:-}" == "DOUBAO_STABLE" && -z "${MINDOS_DOUBAO_ENDPOINT_ID:-}" ]]; then
    warnings+=("Model preset DOUBAO_STABLE is selected but MINDOS_DOUBAO_ENDPOINT_ID is blank.")
  fi
  if [[ "${MINDOS_LLM_ROUTING_STAGE_MAP:-}" == *"llm-fallback:gpt"* ]]; then
    warnings+=("Stage map routes llm-fallback to gpt; check whether this matches your local-first expectation.")
  fi
  if mindos_has_legacy_news_search_serper_config; then
    if [[ -n "$(mindos_effective_news_search_sources)" ]]; then
      warnings+=("Legacy MINDOS_SKILL_NEWS_SEARCH_SERPER_* is set together with unified search-source config; news_search will prefer MINDOS_SKILL_NEWS_SEARCH_SEARCH_SOURCES, then MINDOS_SKILLS_SEARCH_SOURCES.")
    else
      warnings+=("Legacy MINDOS_SKILL_NEWS_SEARCH_SERPER_* is still in use; migrate to MINDOS_SKILLS_SEARCH_SOURCES (or MINDOS_SKILL_NEWS_SEARCH_SEARCH_SOURCES for a news_search-only override).")
    fi
  fi
  if [[ -n "${MINDOS_SKILLS_MCP_SERVERS:-}" && -n "${MINDOS_SKILLS_SEARCH_SOURCES:-}" ]]; then
    warnings+=("Both MINDOS_SKILLS_MCP_SERVERS and MINDOS_SKILLS_SEARCH_SOURCES are set. Keep MCP_SERVERS for generic MCP tools; search sources are loaded in parallel and alias collisions favor explicit MCP server entries.")
  fi
  if [[ -n "${MINDOS_SKILL_NEWS_SEARCH_SEARCH_SOURCES:-}" && -n "${MINDOS_SKILLS_SEARCH_SOURCES:-}" ]]; then
    warnings+=("Both global and news_search-specific search sources are set; news_search will prefer MINDOS_SKILL_NEWS_SEARCH_SEARCH_SOURCES.")
  fi

  if [[ -n "${warnings[*]-}" ]]; then
    printf '%s\n' "${warnings[@]}"
  fi
}

mindos_print_drift_warnings() {
  local warning
  while IFS= read -r warning; do
    [[ -z "$warning" ]] && continue
    echo "[WARN] $warning"
  done < <(mindos_collect_drift_warnings)
}

mindos_print_effective_summary() {
  echo "[INFO] Effective startup summary:"
  echo "[INFO]   MINDOS_MODEL_PRESET=${MINDOS_MODEL_PRESET:-}"
  echo "[INFO]   MINDOS_LLM_PROFILE=${MINDOS_LLM_PROFILE:-}"
  echo "[INFO]   MINDOS_LLM_MODE=${MINDOS_LLM_MODE:-}"
  echo "[INFO]   MINDOS_LLM_PROVIDER=${MINDOS_LLM_PROVIDER:-}"
  echo "[INFO]   MINDOS_LLM_ROUTING_MODE=${MINDOS_LLM_ROUTING_MODE:-}"
  echo "[INFO]   StageMapEntries=$(mindos_count_csv_entries "${MINDOS_LLM_ROUTING_STAGE_MAP:-}")"
  echo "[INFO]   PresetMapEntries=$(mindos_count_csv_entries "${MINDOS_LLM_ROUTING_PRESET_MAP:-}")"
  echo "[INFO]   ProviderEndpointEntries=$(mindos_count_csv_entries "${MINDOS_LLM_PROVIDER_ENDPOINTS:-}")"
  echo "[INFO]   ProviderModelEntries=$(mindos_count_csv_entries "${MINDOS_LLM_PROVIDER_MODELS:-}")"
  echo "[INFO]   ProviderKeyEntries=$(mindos_count_csv_entries "${MINDOS_LLM_PROVIDER_KEYS:-}")"
  echo "[INFO]   McpServerEntries=$(mindos_count_csv_entries "${MINDOS_SKILLS_MCP_SERVERS:-}")"
  echo "[INFO]   SearchSourceEntries=$(mindos_count_csv_entries "${MINDOS_SKILLS_SEARCH_SOURCES:-}")"
  echo "[INFO]   NewsSearchSourceEntries=$(mindos_count_csv_entries "$(mindos_effective_news_search_sources)")"
}
