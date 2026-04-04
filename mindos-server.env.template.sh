#!/bin/bash
# Minimal config loader. Place secrets in mindos-secrets.properties (KEY=value, # or ; for comments).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRETS_FILE="${ROOT_DIR}/mindos-secrets.properties"

export MINDOS_ENV_LOADED=""
export MINDOS_ENV_STAGE="defaults"

# 基本默认（可被 mindos-secrets.properties 覆盖）
: "${MINDOS_SPRING_PROFILE:=solo}"
: "${MINDOS_IM_ENABLED:=true}"
: "${MINDOS_IM_DINGTALK_ENABLED:=true}"
: "${MINDOS_IM_DINGTALK_VERIFY_SIGNATURE:=false}"
: "${MINDOS_IM_DINGTALK_REPLY_TIMEOUT_MS:=2500}"
: "${MINDOS_IM_DINGTALK_REPLY_MAX_CHARS:=1200}"
: "${MINDOS_IM_DINGTALK_STREAM_TOPIC:=/v1.0/im/bot/messages/get}"
: "${MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT:=已收到，正在处理，稍后给你完整回复。}"
: "${MINDOS_IM_DINGTALK_STREAM_FORCE_WAITING:=true}"
: "${MINDOS_IM_DINGTALK_MESSAGE_CARD_ENABLED:=true}"
: "${MINDOS_IM_DINGTALK_MESSAGE_UPDATE_ENABLED:=true}"
: "${MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_INTERVAL_MS:=250}"
: "${MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_DELTA_CHARS:=24}"
: "${MINDOS_IM_DINGTALK_AGENT_STATUS_ENABLED:=true}"
: "${MINDOS_IM_DINGTALK_TOKEN_MONITOR_ENABLED:=true}"
: "${MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY:=}"
: "${MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET:=}"
: "${MINDOS_IM_DINGTALK_OUTBOUND_SEND_URL:=https://api.dingtalk.com/v1.0/im/chat/messages/send}"
: "${MINDOS_IM_DINGTALK_OUTBOUND_UPDATE_URL:=https://api.dingtalk.com/v1.0/im/chat/messages/update}"

# LLM 配置说明（中文详解）
# 本节说明如何在存在多个 provider / 多个 apiKey 的情况下进行路由与配置。
# 核心变量（key/value 都是逗号分隔的 provider 映射）：
# - MINDOS_LLM_PROVIDER_ENDPOINTS
#     格式: provider:baseUrl,provider2:baseUrl2
#     例如: local:http://localhost:11434/api/chat,qwen:https://dashscope.aliyuncs.com/...
# - MINDOS_LLM_PROVIDER_KEYS
#     格式: provider:key,provider2:key2
#     例如: qwen:sk-qwen-xxx,openrouter:sk-or-xxx,openai:sk-oa-xxx
# - MINDOS_LLM_PROVIDER_MODELS
#     格式: provider:modelId,provider2:modelId2
#     例如: qwen:qwen3.5-plus,openai:gpt-4.1
#
# 多 key 情况下的常见配置策略（示例）:
# 1) local 优先 + 云 provider 作为降级/扩展
#    MINDOS_LLM_PROFILE=CUSTOM_LOCAL_FIRST
#    MINDOS_LLM_PROVIDER_ENDPOINTS=local:http://localhost:11434/api/chat,qwen:https://dashscope...
#    MINDOS_LLM_PROVIDER_KEYS=qwen:sk-qwen-xxx
#    说明: 本地模型负责语义分析与大部分低成本请求；当本地不满足或需要云能力时，按 routing 设置调用 qwen
#
# 2) 多 cloud provider 并存（按场景路由）
#    MINDOS_LLM_PROVIDER_ENDPOINTS=qwen:https://...,openrouter:https://...,openai:https://...
#    MINDOS_LLM_PROVIDER_KEYS=qwen:sk-qwen,openrouter:sk-or,openai:sk-oa
#    MINDOS_LLM_ROUTING_STAGE_MAP=llm-dsl:openrouter,llm-fallback:qwen
#    MINDOS_LLM_ROUTING_PRESET_MAP=cost:qwen,quality:openai
#    说明: stage map 指定不同任务阶段使用哪个 provider；preset map 可按 cost/quality 选不同 provider
#
# 3) 单 provider（生产/简化）
#    MINDOS_LLM_PROFILE=QWEN_STABLE
#    MINDOS_LLM_PROVIDER=qwen
#    MINDOS_LLM_PROVIDER_KEYS=qwen:sk-qwen-xxx
#    说明: 最简单、可审计且易轮换密钥的配置
#
# 注意事项与校验：
# - 键名和值中不要包含空格，map 以逗号分隔，每个条目内部以第一个冒号分隔 provider 与 value。
# - 脚本中有格式校验函数 mindos_validate_kv_map_format，会在启动/发布时检查 map 的语法并报错。
# - 为避免在 dist 模板中产生噪音，请把真实的 API keys 写入 release 专用文件（mindos-secrets.release.properties）或由 CI 在发布时注入。
# - local endpoint（local:http://localhost:11434/api/chat）建议作为语义分析/省 token 的可选端点，与云 provider 并存，优先级由 profile/routing 决定。
#
# 默认路由示例（模板保留最小默认，实际请在 release 中设置 keys）
: "${MINDOS_LLM_PROFILE:=QWEN_STABLE}"
: "${MINDOS_LLM_MODE:=QWEN_NATIVE}"
: "${MINDOS_LLM_PROVIDER:=qwen}"
: "${MINDOS_IM_DINGTALK_ENABLED:=true}"
: "${MINDOS_IM_DINGTALK_VERIFY_SIGNATURE:=false}"
: "${MINDOS_IM_DINGTALK_REPLY_TIMEOUT_MS:=2500}"
: "${MINDOS_IM_DINGTALK_REPLY_MAX_CHARS:=1200}"
: "${MINDOS_IM_DINGTALK_STREAM_TOPIC:=/v1.0/im/bot/messages/get}"
: "${MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT:=已收到，正在处理，稍后给你完整回复。}"
: "${MINDOS_IM_DINGTALK_STREAM_FORCE_WAITING:=true}"
: "${MINDOS_IM_DINGTALK_MESSAGE_CARD_ENABLED:=true}"
: "${MINDOS_IM_DINGTALK_MESSAGE_UPDATE_ENABLED:=true}"
: "${MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_INTERVAL_MS:=250}"
: "${MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_DELTA_CHARS:=24}"
: "${MINDOS_IM_DINGTALK_AGENT_STATUS_ENABLED:=true}"
: "${MINDOS_IM_DINGTALK_TOKEN_MONITOR_ENABLED:=true}"
: "${MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY:=}"
: "${MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET:=}"
: "${MINDOS_IM_DINGTALK_OUTBOUND_SEND_URL:=https://api.dingtalk.com/v1.0/im/chat/messages/send}"
: "${MINDOS_IM_DINGTALK_OUTBOUND_UPDATE_URL:=https://api.dingtalk.com/v1.0/im/chat/messages/update}"

export MINDOS_SPRING_PROFILE
export MINDOS_IM_ENABLED
export MINDOS_IM_DINGTALK_ENABLED
export MINDOS_IM_DINGTALK_VERIFY_SIGNATURE
export MINDOS_IM_DINGTALK_REPLY_TIMEOUT_MS
export MINDOS_IM_DINGTALK_REPLY_MAX_CHARS
export MINDOS_IM_DINGTALK_STREAM_TOPIC
export MINDOS_IM_DINGTALK_STREAM_WAITING_TEXT
export MINDOS_IM_DINGTALK_STREAM_FORCE_WAITING
export MINDOS_IM_DINGTALK_MESSAGE_CARD_ENABLED
export MINDOS_IM_DINGTALK_MESSAGE_UPDATE_ENABLED
export MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_INTERVAL_MS
export MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_DELTA_CHARS
export MINDOS_IM_DINGTALK_AGENT_STATUS_ENABLED
export MINDOS_IM_DINGTALK_TOKEN_MONITOR_ENABLED
export MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY
export MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET
export MINDOS_IM_DINGTALK_OUTBOUND_SEND_URL
export MINDOS_IM_DINGTALK_OUTBOUND_UPDATE_URL

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


