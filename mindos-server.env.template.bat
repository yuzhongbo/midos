@echo off
REM Minimal config loader. Place secrets in mindos-secrets.properties (KEY=value, # or ; for comments).

set "ROOT_DIR=%~dp0"
set "MINDOS_ENV_LOADED="
set "MINDOS_ENV_STAGE=boot"

if exist "%ROOT_DIR%mindos-secrets.properties" (
  set "MINDOS_ENV_STAGE=loading_secrets"
  for /f "usebackq tokens=1,* delims==" %%A in (`findstr /R /V /C:"^[ ]*[#;]" "%ROOT_DIR%mindos-secrets.properties"`) do (
    if not "%%A"=="" set "%%A=%%B"
  )
)

set "MINDOS_ENV_STAGE=defaults"

REM 基本默认（仅示例，可被 mindos-secrets.properties 覆盖）
if not defined MINDOS_SPRING_PROFILE set "MINDOS_SPRING_PROFILE=solo"
if not defined MINDOS_SERVER_PORT set "MINDOS_SERVER_PORT=8080"
if not defined MINDOS_MEMORY_FILE_REPO_ENABLED set "MINDOS_MEMORY_FILE_REPO_ENABLED=true"
if not defined MINDOS_MEMORY_FILE_REPO_BASE_DIR set "MINDOS_MEMORY_FILE_REPO_BASE_DIR=data/memory-sync"
if not defined MINDOS_PAUSE_ON_EXIT set "MINDOS_PAUSE_ON_EXIT=true"

REM LLM 配置说明（中文详解）
REM 本节说明如何在存在多个 provider / 多个 apiKey 的情况下进行路由与配置。
REM 核心变量（key/value 都是逗号分隔的 provider 映射）：
REM - MINDOS_LLM_PROVIDER_ENDPOINTS
REM     格式: provider:baseUrl,provider2:baseUrl2
REM     例如: local:http://localhost:11434/api/chat,qwen:https://dashscope.aliyuncs.com/...
REM - MINDOS_LLM_PROVIDER_KEYS
REM     格式: provider:key,provider2:key2
REM     例如: qwen:sk-qwen-xxx,openrouter:sk-or-xxx,openai:sk-oa-xxx
REM - MINDOS_LLM_PROVIDER_MODELS
REM     格式: provider:modelId,provider2:modelId2
REM     例如: qwen:qwen3.5-plus,openai:gpt-4.1
REM
REM 多 key 情况下的常见配置策略（示例）:
REM 1) local 优先 + 云 provider 作为降级/扩展
REM    set "MINDOS_LLM_PROFILE=CUSTOM_LOCAL_FIRST"
REM    REM set "MINDOS_LLM_PROVIDER_ENDPOINTS=local:http://localhost:11434/api/chat,qwen:https://dashscope..."
REM    REM set "MINDOS_LLM_PROVIDER_KEYS=qwen:sk-qwen-xxx"
REM    说明: 本地模型负责语义分析与大部分低成本请求；当本地不满足或需要云能力时，按 routing 设置调用 qwen
REM
REM 2) 多 cloud provider 并存（按场景路由）
REM    REM set "MINDOS_LLM_PROVIDER_ENDPOINTS=qwen:https://...,openrouter:https://...,openai:https://..."
REM    REM set "MINDOS_LLM_PROVIDER_KEYS=qwen:sk-qwen,openrouter:sk-or,openai:sk-oa"
REM    REM set "MINDOS_LLM_ROUTING_STAGE_MAP=llm-dsl:openrouter,llm-fallback:qwen"
REM    REM set "MINDOS_LLM_ROUTING_PRESET_MAP=cost:qwen,quality:openai"
REM    说明: stage map 指定不同任务阶段使用哪个 provider；preset map 可按 cost/quality 选不同 provider
REM
REM 3) 单 provider（生产/简化）
REM    set "MINDOS_LLM_PROFILE=QWEN_STABLE"
REM    set "MINDOS_LLM_PROVIDER=qwen"
REM    set "MINDOS_LLM_PROVIDER_KEYS=qwen:sk-qwen-xxx"
REM    说明: 最简单、可审计且易轮换密钥的配置
REM
REM 注意事项与校验：
REM - 键名和值中不要包含空格，map 以逗号分隔，每个条目内部以第一个冒号分隔 provider 与 value。
REM - 脚本中有格式校验函数 mindos_validate_kv_map_format，会在启动/发布时检查 map 的语法并报错。
REM - 为避免在 dist 模板中产生噪音，请把真实的 API keys 写入 release 专用文件（mindos-secrets.release.properties）或由 CI 在发布时注入。
REM - local endpoint（local:http://localhost:11434/api/chat）建议作为语义分析/省 token 的可选端点，与云 provider 并存，优先级由 profile/routing 决定。

REM 默认路由示例（模板保留最小默认，实际请在 release 中设置 keys）
if not defined MINDOS_LLM_PROFILE set "MINDOS_LLM_PROFILE=QWEN_STABLE"
if not defined MINDOS_LLM_MODE set "MINDOS_LLM_MODE=QWEN_NATIVE"
if not defined MINDOS_LLM_PROVIDER set "MINDOS_LLM_PROVIDER=qwen"


REM 不要在模板中保留大量 REPLACE_WITH_* 的活跃赋值，避免在预检中产生噪音。
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
if not defined MINDOS_IM_DINGTALK_STREAM_FORCE_WAITING set "MINDOS_IM_DINGTALK_STREAM_FORCE_WAITING=true"
if not defined MINDOS_IM_DINGTALK_MESSAGE_CARD_ENABLED set "MINDOS_IM_DINGTALK_MESSAGE_CARD_ENABLED=true"
if not defined MINDOS_IM_DINGTALK_MESSAGE_UPDATE_ENABLED set "MINDOS_IM_DINGTALK_MESSAGE_UPDATE_ENABLED=true"
if not defined MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_INTERVAL_MS set "MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_INTERVAL_MS=250"
if not defined MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_DELTA_CHARS set "MINDOS_IM_DINGTALK_CARD_UPDATE_MIN_DELTA_CHARS=24"
if not defined MINDOS_IM_DINGTALK_AGENT_STATUS_ENABLED set "MINDOS_IM_DINGTALK_AGENT_STATUS_ENABLED=true"
if not defined MINDOS_IM_DINGTALK_TOKEN_MONITOR_ENABLED set "MINDOS_IM_DINGTALK_TOKEN_MONITOR_ENABLED=true"
if not defined MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE set "MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE="
if not defined MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY set "MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY="
if not defined MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET set "MINDOS_IM_DINGTALK_OUTBOUND_APP_SECRET="
if not defined MINDOS_IM_DINGTALK_OUTBOUND_SEND_URL set "MINDOS_IM_DINGTALK_OUTBOUND_SEND_URL=https://api.dingtalk.com/v1.0/im/chat/messages/send"
if not defined MINDOS_IM_DINGTALK_OUTBOUND_UPDATE_URL set "MINDOS_IM_DINGTALK_OUTBOUND_UPDATE_URL=https://api.dingtalk.com/v1.0/im/chat/messages/update"

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

