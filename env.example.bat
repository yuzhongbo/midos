@echo off
REM MindOS minimal runtime environment (Windows)
REM Copy this file to env.bat and fill in only the secrets or endpoints you need at deploy time.
REM Other behavioral settings stay in application.properties / application-solo.properties.

REM === LLM runtime secrets ===
set MINDOS_LLM_PROVIDER=openai
set MINDOS_LLM_API_KEY=replace-with-your-model-api-key
REM Optional: override the endpoint if your provider is not the default OpenAI-compatible URL
set MINDOS_LLM_ENDPOINT=https://api.openai.com/v1/chat/completions
REM Optional: provider-specific key map (comma separated, only if you route multiple providers)
REM set MINDOS_LLM_PROVIDER_KEYS=deepseek:sk-xxx,qwen:sk-yyy,kimi:sk-zzz

REM === DingTalk webhook ===
REM Enable IM webhook handling only when you actually deploy it
set MINDOS_IM_ENABLED=true
set MINDOS_IM_DINGTALK_ENABLED=true
REM DingTalk webhook signing secret
set MINDOS_IM_DINGTALK_SECRET=replace-with-dingtalk-signing-secret

REM === Other IM platforms (optional) ===
REM set MINDOS_IM_FEISHU_ENABLED=true
REM set MINDOS_IM_FEISHU_SECRET=replace-with-feishu-secret
REM set MINDOS_IM_WECHAT_ENABLED=true
REM set MINDOS_IM_WECHAT_TOKEN=replace-with-wechat-token

REM === Start assistant-api ===
REM Pass through any extra JVM args after the script name, e.g. env.bat --spring.profiles.active=solo
java -jar assistant-api/target/assistant-api-0.1.0-SNAPSHOT.jar %*
