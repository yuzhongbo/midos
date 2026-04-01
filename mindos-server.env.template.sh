#!/bin/bash
# Minimal config loader. Place secrets in mindos-secrets.properties (KEY=value, # or ; for comments).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRETS_FILE="${ROOT_DIR}/mindos-secrets.properties"

export MINDOS_ENV_LOADED=""
export MINDOS_ENV_STAGE="boot"

if [[ -f "$SECRETS_FILE" ]]; then
  export MINDOS_ENV_STAGE="loading_secrets"
  while IFS='=' read -r raw_key raw_val; do
    key_head="${raw_key#${raw_key%%[![:space:]]*}}"
    [[ -z "$key_head" ]] && continue
    case "$key_head" in
      \#*|\;*) continue ;;
    esac
    key="${raw_key%% *}"
    val="${raw_val}"
    [[ -z "$key" ]] && continue
    export "${key}"="${val}"
  done < "$SECRETS_FILE"
fi

export MINDOS_ENV_STAGE="defaults"

: "${MINDOS_SPRING_PROFILE:=solo}"
: "${MINDOS_IM_ENABLED:=true}"
: "${MINDOS_IM_DINGTALK_ENABLED:=true}"
: "${MINDOS_IM_DINGTALK_VERIFY_SIGNATURE:=false}"
: "${MINDOS_IM_DINGTALK_REPLY_TIMEOUT_MS:=2500}"
: "${MINDOS_IM_DINGTALK_REPLY_MAX_CHARS:=1200}"
: "${MINDOS_IM_DINGTALK_STREAM_TOPIC:=/v1.0/im/bot/messages/get}"
: "${MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY:=}"
: "${MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET:=}"

export MINDOS_SPRING_PROFILE
export MINDOS_IM_ENABLED
export MINDOS_IM_DINGTALK_ENABLED
export MINDOS_IM_DINGTALK_VERIFY_SIGNATURE
export MINDOS_IM_DINGTALK_REPLY_TIMEOUT_MS
export MINDOS_IM_DINGTALK_REPLY_MAX_CHARS
export MINDOS_IM_DINGTALK_STREAM_TOPIC
export MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY
export MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET

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
    export MINDOS_IM_DINGTALK_STREAM_ENABLED=true
  else
    export MINDOS_IM_DINGTALK_STREAM_ENABLED=false
  fi
fi

if [[ -z "${MINDOS_IM_DINGTALK_OUTBOUND_ENABLED:-}" ]]; then
  if [[ -n "${MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE:-}" && -n "${MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY:-}" && -n "${MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET:-}" ]]; then
    export MINDOS_IM_DINGTALK_OUTBOUND_ENABLED=true
  else
    export MINDOS_IM_DINGTALK_OUTBOUND_ENABLED=false
  fi
fi

export MINDOS_ENV_STAGE="done"
export MINDOS_ENV_LOADED="1"


