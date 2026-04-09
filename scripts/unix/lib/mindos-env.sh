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