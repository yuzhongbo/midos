# MindOS

MindOS is a lightweight, single-user personal AI assistant backend built with Java 17 and Spring Boot 3.2.x.

## Modules
- `assistant-api`: Spring Boot REST API (`/chat`, backward compatible with `/api/chat`)
- `assistant-dispatcher`: routes user input to skills or LLM fallback
- `assistant-memory`: in-memory episodic, semantic, and procedural memory
- `assistant-skill`: skill interface, registry, DSL execution, example skills, MCP tool adapter, and loader extensions
- `assistant-llm`: API-key-based external LLM client skeleton
- `assistant-common`: shared contracts (`SkillDsl`, `SkillContext`, `SkillResult`, `LlmClient`)
- `assistant-sdk`: Java client SDK for calling MindOS server endpoints
- `mindos-cli`: Picocli-based command line client built on top of `assistant-sdk`; starts interactive chat mode by default with slash commands for session/profile/memory actions

## Dependency flow
`assistant-api -> assistant-dispatcher -> (assistant-skill, assistant-memory, assistant-llm) -> assistant-common`

`mindos-cli -> assistant-sdk -> assistant-common`

## Quick try
```bash
./mvnw -q test
./mvnw -pl assistant-api -am spring-boot:run -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8
```

## Minimal runtime env (Windows)

To avoid re-editing property files for every deployment, copy `env.example.bat` to `env.bat`, fill only the runtime secrets, then launch the server through that script (it forwards any extra args like `--spring.profiles.active=solo`).

- LLM: `MINDOS_LLM_PROVIDER`, `MINDOS_LLM_API_KEY`, optionally `MINDOS_LLM_ENDPOINT` / `MINDOS_LLM_PROVIDER_KEYS` if you route multiple providers.
- DingTalk webhook: set `MINDOS_IM_ENABLED=true`, `MINDOS_IM_DINGTALK_ENABLED=true`, and provide `MINDOS_IM_DINGTALK_SECRET`.
- Optional IM: uncomment Feishu/WeChat lines if you need those platforms.
- All other functional toggles stay in `assistant-api/src/main/resources/application.properties` (and `application-solo.properties` for solo profile).

## Solo experience mode

Use the built-in `solo` Spring profile when you are the only user and want better day-to-day experience without changing CLI command behavior.

```bash
./mvnw -pl assistant-api -am spring-boot:run -Dspring-boot.run.profiles=solo -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8
```

Or use the one-click launcher:

```bash
chmod +x ./run-mindos-solo.sh
./run-mindos-solo.sh
```

Reusable solo helper scripts:

```bash
chmod +x ./solo-cli.sh ./solo-smoke.sh ./solo-stop.sh
./solo-cli.sh --help
./solo-smoke.sh --help
./solo-stop.sh --help
```

- `solo-cli.sh`: starts `mindos-cli` with default `--server`/`--user` only (no command semantics change)
- `solo-smoke.sh`: lightweight local checks for `/chat` echo route and `/api/metrics/llm`
- `solo-stop.sh`: stops local service by port and/or process-name pattern

Windows self-hosted equivalents:

```bat
run-mindos-solo.bat
solo-smoke.bat
install-mindos-server.bat
```

- `run-mindos-solo.bat`: runs `assistant-api` from source with `solo` profile via `mvnw.cmd`
- `solo-smoke.bat`: checks `/chat` echo route and `/api/metrics/llm` using PowerShell
- `install-mindos-server.bat`: builds `assistant-api`, installs `%USERPROFILE%\.mindos-server`, generates `mindos-server.bat` / `mindos-server-smoke.bat`, and seeds `mindos-secrets.properties` with the current DingTalk stream variables

`solo` profile highlights:
- enables short-TTL LLM cache for repeated prompts
- defaults to `qwen` as the main provider (`qwen3.5-plus` when no explicit model is supplied)
- keeps richer prompt context and slightly longer replies
- delays conversation rollup so recent dialogue stays hot longer
- disables admin token requirement for `GET /api/metrics/llm` on local single-user setups

CLI command mode remains unchanged. You can keep using the current interaction style and slash commands.

### Solo daily commands (CLI mode unchanged)

```bash
./solo-cli.sh
```

Optional overrides:

```bash
MINDOS_SERVER=http://localhost:8080 MINDOS_USER=local-user ./solo-cli.sh --show-routing-details
MINDOS_SERVER=http://localhost:8080 ./solo-smoke.sh
./solo-stop.sh --port 8080
```

### Windows quick start (package + run)

Requirements: Java 17 in `PATH`.

```bat
install-mindos-server.bat
notepad %USERPROFILE%\.mindos-server\mindos-secrets.properties
%USERPROFILE%\.mindos-server\mindos-server.bat
%USERPROFILE%\.mindos-server\mindos-server-smoke.bat
```

`MINDOS_IM_DINGTALK_REPLY_TIMEOUT_MS` is the synchronous webhook wait budget. Keep it modest; raising it too high does not make DingTalk wait forever, it only increases the chance that the platform times out first.

To switch a DingTalk bot to long-connection stream mode, fill in `MINDOS_IM_DINGTALK_STREAM_CLIENT_ID`, `MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET`, and `MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE` in `mindos-secrets.properties`; the installed launcher auto-enables stream/outbound when those values are present. In the current DingTalk console naming, `Client ID` is the old `AppKey` / `SuiteKey`, `Client Secret` is the old `AppSecret` / `SuiteSecret`, and the current stream integration does not read `AgentId` directly.

Source-run option (without packaging):

```bat
run-mindos-solo.bat
solo-smoke.bat
```

### Export a portable Windows server bundle from the dev machine

Build a copy-ready bundle containing the executable JAR plus `mindos-server.bat`, `mindos-server-smoke.bat`, and `mindos-server-stop.bat`:

```bash
chmod +x ./export-mindos-windows-dist.sh
./export-mindos-windows-dist.sh ./dist/mindos-windows-server
```

The command above writes the bundle to the project root at `dist/mindos-windows-server`.

Copy the whole output directory to the Windows host, then run:

```bat
notepad mindos-secrets.properties
mindos-server.bat
mindos-server-smoke.bat
mindos-server-stop.bat
```

`mindos-secrets.properties` is the main place to edit secrets, while `mindos-server.env.bat` keeps defaults and compatibility logic:
- keep each line in the form `KEY=value` and edit only the text to the right of the first `=`
- for DingTalk stream mode, fill `MINDOS_IM_DINGTALK_STREAM_CLIENT_ID`, `MINDOS_IM_DINGTALK_STREAM_CLIENT_SECRET`, and `MINDOS_IM_DINGTALK_OUTBOUND_ROBOT_CODE`
- `MINDOS_IM_DINGTALK_OUTBOUND_APP_KEY` / `_APP_SECRET` are optional; when blank, the bundle reuses the stream clientId/clientSecret
- legacy `MINDOS_IM_DINGTALK_APP_KEY` / `_APP_SECRET` names are still accepted by the exported bundle
- edit `mindos-server.env.bat` only when you need to override non-secret defaults such as provider mode or ports
- if startup logs show `{"event":"dingtalk.stream.lifecycle.skipped","reason":"stream_mode_disabled"}`, the DingTalk stream credentials or enable flags were not populated

Common `mindos-server.env.bat` overrides:
- choose provider profile in `mindos-secrets.properties`:
  - `MINDOS_LLM_PROFILE=QWEN_STABLE` (single-vendor qwen, model from `MINDOS_QWEN_MODEL`)
  - `MINDOS_LLM_PROFILE=DOUBAO_STABLE` (single-vendor doubao, model from `MINDOS_DOUBAO_ENDPOINT_ID`)
  - `MINDOS_LLM_PROFILE=CN_DUAL` (llm-dsl uses qwen, llm-fallback prefers doubao)
  - `MINDOS_LLM_PROFILE=OPENAI_NATIVE` (single-vendor openai, model from `MINDOS_OPENAI_MODEL`)
  - `MINDOS_LLM_PROFILE=GEMINI_NATIVE` (single-vendor gemini native endpoint, model from `MINDOS_GEMINI_MODEL`)
  - `MINDOS_LLM_PROFILE=GROK_NATIVE` (single-vendor grok, model from `MINDOS_GROK_MODEL`)
  - `MINDOS_LLM_PROFILE=OPENROUTER_INTENT` (gpt/grok/gemini over OpenRouter)
- each profile auto-sets `MINDOS_LLM_MODE`, `MINDOS_LLM_PROVIDER`, stage-map, endpoint map, key map, and model map
- if you switch to Doubao Ark, set a real `MINDOS_DOUBAO_ENDPOINT_ID` because Ark Chat requires a valid Model/Endpoint ID plus `Authorization: Bearer <ARK_API_KEY>`
- if you switch to OpenRouter intent routing, fill `MINDOS_OPENROUTER_KEY`
- add other providers only when you actually need cross-provider switching
- keep `MINDOS_IM_DINGTALK_*` / `MINDOS_IM_WECHAT_*` disabled until you have real bot credentials
- `MINDOS_SERVER_PORT` for local service port

The exported Windows bundle also includes `mindos-server.full.env.bat` as a commented multi-provider reference. It is not auto-loaded; copy only the lines you want from it back into `mindos-server.env.bat` when needed. In provider maps, commas split entries and the first colon splits provider name from value, so edit those lines carefully.

LLM provider configuration notes:
- `mindos.llm.provider-endpoints` controls provider -> chat endpoint mapping.
- `mindos.llm.provider-models` controls provider -> model mapping.
- `mindos.llm.profile` (or env `MINDOS_LLM_PROFILE`) can pin stable provider defaults: `QWEN_STABLE` -> `qwen`, `DOUBAO_STABLE` -> `doubao`.
- startup logs include `llm.routing.profile.effective`, and first-hit samples include `llm.call.routing.sample` (`routeStage` -> `provider/model`) for quick routing verification.
- `qwen` keeps the built-in default model `qwen3.5-plus` when no model is supplied.
- `doubao` must be configured with a real Ark `Model ID` or `Endpoint ID`, for example `mindos.llm.provider-models=doubao:ep-202603290001`, before enabling live HTTP calls.

## Cloud deploy (single-user)

Recommended flow: initialize SSH key once, then deploy without password.

```bash
chmod +x ./init-authorized-keys.sh ./cloud-init.sh ./cloud-check.sh ./deploy-cloud.sh ./rollback-cloud.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./init-authorized-keys.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./cloud-init.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./deploy-cloud.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./cloud-check.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./rollback-cloud.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./cloud-check.sh
```

Temporary fallback (not recommended for long-term use):

```bash
CLOUD_HOST=1.2.3.4 CLOUD_USER=root CLOUD_PASS='***' ./deploy-cloud.sh
```

Notes:
- Deploy keeps `releases/<timestamp>` and updates `current` / `previous` symlinks for quick rollback.
- `rollback-cloud.sh` switches `current` to `previous` and restarts `assistant-api`.
- `cloud-init.sh` initializes remote directories/permissions and runs baseline checks.
- `cloud-check.sh` prints `[PASS]/[WARN]/[FAIL]` with `SUMMARY`; exits `1` when FAIL exists.
- Remote logs are written to `$REMOTE_BASE_DIR/logs/assistant-api.out`.

In chat window:
- `我有哪些技能`
- `查看我的记忆风格`
- `按我的风格压缩这段记忆：明天先拆任务再推进联调`
- `创建任务：整理接口清单，截止周五`
- `打开排障模式`

## CLI quick try

### 新手快速上手（3 分钟，自然语言，推荐）

如果你不熟悉命令行，先做下面 3 步：

```bash
./mvnw -q -pl mindos-cli -am test
./mvnw -q -pl mindos-cli -am package
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication
```

进入会话后，直接用自然语言输入即可（不需要记参数）：
- `我有哪些技能`
- `帮我拉取最近 30 条记忆`
- `给学生 stu-1 做数学学习计划，六周，每周八小时`
- `打开排障模式` / `关闭排障模式`

看不懂技术命令也没关系：默认使用自然语言即可；只有排障时才需要 `/help full`。

### 高级参数化启动（可选）

基础启动与显示：

```bash
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="--help"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="--server http://localhost:8080 --user local-user"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="--theme cyber"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="--show-routing-details --theme cyber"
```

Profile 管理：

```bash
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="init --name BoAssistant --role coding-partner --style concise --language zh-CN --timezone Asia/Shanghai"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="profile show"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="profile set --style detailed --timezone UTC"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="profile set --llm-provider openai"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="profile reset"
```

Chat 与记忆操作：

```bash
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="chat --user local-user --message 'echo hello' --server http://localhost:8080"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="chat --interactive --server http://localhost:8080"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="memory pull --user local-user --since 0 --limit 100 --server http://localhost:8080"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="memory push --user local-user --file /tmp/memory-sync.json --limit 100 --server http://localhost:8080"
```

### 更多自然语言示例（可选）

上面的“新手快速上手”已经覆盖高频用法；这里补充更多自然语言到系统动作的映射示例：

日常对话与记忆：
- `帮我从 12 开始拉取最近 30 条记忆` -> `/memory pull --since 12 --limit 30`
- `从 12 开始拉取 30 条记忆` -> `/memory pull --since 12 --limit 30`
- `按我的风格压缩这段记忆：明天先整理目标，再拆任务` -> `/memory compress --source ...`
- `查看最近 5 条历史` / `给我看几条历史` -> `/history --limit 5` / `/history --limit 10`
- `查看最近十条历史` / `帮我保存二十条记忆` -> `/history --limit 10` / `/memory push --limit 20`
- `从 2 开始拉三十条` -> `/memory pull --since 2 --limit 30`
- `查看最近一千二百条历史` / `从 3 开始拉一千零二十条记忆` -> `/history --limit 1200` / `/memory pull --since 3 --limit 1020`
- `查看最近一万二千条历史` / `从 3 开始拉一万零二十条记忆` -> `/history --limit 12000` / `/memory pull --since 3 --limit 10020`

会话与配置管理：
- `把用户改为 dev-user` -> `/user dev-user`
- `把模型切换到 openai` / `取消模型覆盖` -> `/provider openai` / `/provider default`
- `查看我的记忆风格` / `把记忆风格改成 action，语气 warm，格式 bullet` -> `/memory style show` / `/memory style set ...`
- `请帮我做心理分析，我和同事沟通卡住了，用职场版，优先级聚焦 p1` -> `/eq coach --query ... --style workplace --mode analysis --priority-focus p1`

网络与扩展接入（通常用于高级/排障场景）：
- `把服务地址换成 http://localhost:18080` -> `/server http://localhost:18080`（需确认）
- `把服务端地址改成 localhost:19090` -> `/server http://localhost:19090`（自动补全协议，需确认）
- `请接入mcp https://docs.example.com/mcp，简称 docs-cn` -> `/skill load-mcp --alias docs-cn --url ...`（需确认）

### 语义分析 skill 与前置意图整理

- 内置 `semantic.analyze` skill，可显式查看系统如何理解你的请求，例如：
  - `semantic 帮我修复 Spring 接口 bug`
  - `请先做语义分析：帮我创建一个待办，截止周五前提交周报`
- Dispatcher 默认会先做一层轻量语义整理，再决定：
  - 直接路由到本地 skill
  - 交给 LLM 做自然回复
  - 把语义整理结果附加到后续提示词中，提升意图理解准确率
- 可选配置：
  - `mindos.dispatcher.semantic-analysis.enabled=true`：启用语义整理
  - `mindos.dispatcher.semantic-analysis.llm-enabled=true`：允许额外调用 LLM 做语义分析
    - `solo` / Windows 分发默认可通过 `MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_LLM_ENABLED=true` 开启，适合低置信度自然语言请求（如新闻、搜索、模糊意图）的二次理解
  - `mindos.dispatcher.semantic-analysis.delegate-skill=mcp.<alias>.<tool>`：把语义分析委托给已接入的 MCP skill
  - `mindos.dispatcher.semantic-analysis.route-min-confidence=0.72`：语义分析直接路由到本地 skill 的最小置信度

### 高级/排障命令速查（可选）

仅在排障或高级场景使用；平时建议直接自然语言输入。

```text
/help
/help full
/session
/user <userId>
/server <url>
/provider <name|default>
/history --limit 10
/retry
/clear
/skills
/skill list
/skill reload
/skill reload-mcp
/skill load-mcp --alias docs --url http://localhost:8081/mcp
/skill load-jar --url https://example.com/skill-weather.jar
/profile show
/profile set
/profile set --name BoAssistant --role coding-partner --style detailed --language zh-CN --timezone UTC --llm-provider openai
/profile reset
/memory pull --since 0 --limit 50
/memory push
/memory push --limit 50
/memory push --file /tmp/memory-sync.json --limit 50
/memory style show
/memory style set --style-name action --tone warm --output-format bullet
/memory compress --source 这周先整理目标，再拆任务
/eq coach --query 我最近和同事沟通卡住了 --style workplace --mode both --priority-focus p1
/exit
```

CLI 默认是自然聊天视图：只看对话结果，不展示 skill/channel/路由等技术细节；需要排障时，直接说“打开排障模式/关闭排障模式”即可切换（`--show-routing-details` 与 `--pure-nl` 仍保留兼容）。

`/help` 默认给自然语言操作提示，`/help full` 提供完整技术命令。交互模式中高风险操作（重置 profile、加载外部 JAR/MCP、切换 server）会二次确认；`/memory pull` 与 `/memory push` 后台异步执行，不阻塞聊天。`/memory push` 支持在窗口内逐步录入 `semantic / episodic / procedural`（或 `语义 / 对话 / 流程`），并在提交前预览，服务端会做规范化、去重与检索优化。

`memory push` payload example (`/tmp/memory-sync.json`):
```json
{
  "eventId": "evt-terminal-a-1",
  "episodic": [
    {"role": "user", "content": "hello from terminal A", "createdAt": "2026-03-13T00:00:00Z"}
  ],
  "semantic": [],
  "procedural": []
}
```

## Sample requests

Chat requests:
```bash
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"userId":"local-user","message":"echo hello"}'

curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"userId":"local-user","message":"skill:time"}'

curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"userId":"local-user","message":"你有哪些技能？"}'

curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"userId":"local-user","message":"你还可以学习哪些技能？"}'

curl http://localhost:8080/api/chat/local-user/history
```

Memory sync requests:
```bash
curl -X POST http://localhost:8080/api/memory/local-user/sync \
  -H 'Content-Type: application/json' \
  -d '{
    "episodic": [{"role": "user", "content": "hello from terminal A"}],
    "semantic": [{"text": "order api design", "embedding": [1.0, 0.2]}],
    "procedural": [{"skillName": "code.generate", "input": "create order api", "success": true}]
  }'

curl "http://localhost:8080/api/memory/local-user/sync?since=0&limit=100"
```

Skill management requests:
```bash
curl http://localhost:8080/api/skills

curl -X POST http://localhost:8080/api/skills/reload

curl -X POST http://localhost:8080/api/skills/load-jar \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/skill-weather.jar"}'

curl -X POST http://localhost:8080/api/skills/reload-mcp

curl -X POST http://localhost:8080/api/skills/load-mcp \
  -H 'Content-Type: application/json' \
  -d '{"alias":"docs","url":"http://localhost:8081/mcp"}'

curl -X POST http://localhost:8080/api/skills/load-mcp \
  -H 'Content-Type: application/json' \
  -d '{"alias":"github","url":"https://example.com/mcp","headers":{"Authorization":"Bearer <token>"}}'
```

## Example response
```json
{
  "reply": "hello",
  "channel": "echo"
}
```

## Notes
- Base profile keeps LLM calls in skeleton mode unless `mindos.llm.http.enabled=true` is enabled together with a valid key/endpoint map.
- `solo` profile enables real OpenAI-compatible HTTP calls by default; other profiles can opt in with `mindos.llm.http.enabled=true`.
- `gemini` supports both OpenAI-compatible proxy endpoints (for example `/v1/chat/completions`) and Google's native `.../v1beta/models/<model>:generateContent` endpoint.
- `qwen` uses DashScope's OpenAI-compatible endpoint: `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`.
- When no explicit `model` is supplied, `qwen` defaults to `qwen3.5-plus`.
- For native Gemini, prefer configuring the base endpoint in `mindos.llm.provider-endpoints` and keep the secret in `mindos.llm.provider-keys`; the client will append `?key=...` automatically and mask it in metrics.
- DingTalk webhook replies are synchronous; `mindos.im.dingtalk.reply-timeout-ms` (default `2500`) caps the wait budget so slow LLM replies degrade to a timeout-friendly message instead of vanishing from the chat window.
- DingTalk reply length guard `mindos.im.dingtalk.reply-max-chars` (default `1200`) trims oversized webhook replies; in stream mode, MindOS sends long final answers in ordered segments (`<= reply-max-chars` each) to reduce information loss.
- DingTalk stream mode is optional and intended for slow replies: enable `mindos.im.dingtalk.stream.enabled=true`, provide `mindos.im.dingtalk.stream.client-id`, `mindos.im.dingtalk.stream.client-secret`, and outbound settings (`mindos.im.dingtalk.outbound.enabled=true`, `mindos.im.dingtalk.outbound.robot-code`). When a reply is slow, MindOS sends a waiting status after `mindos.im.dingtalk.stream.waiting-delay-ms` (default `800`) and then pushes the final answer when it is ready.
- For single-host restart continuity, central memory uses file storage by default (`mindos.memory.file-repo.enabled=true`, `mindos.memory.file-repo.base-dir=data/memory-sync`) when no JDBC `DataSource` is configured.
- Optional multi-provider routing:
  - default provider: `mindos.llm.provider=stub`
  - routing mode: `mindos.llm.routing.mode=fixed|auto` (default `fixed`)
  - real HTTP switch: `mindos.llm.http.enabled=true|false` (default `false` in base profile)
  - auto stage mapping: `mindos.llm.routing.stage-map=llm-dsl:openai,llm-fallback:openai`
  - preset mapping: `mindos.llm.routing.preset-map=cost:openai,balanced:openai,quality:openai`
  - provider endpoint map: `mindos.llm.provider-endpoints=openai:https://api.openai.com/v1/chat/completions,local:http://localhost:11434/v1/chat/completions,gemini:https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent,qwen:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
  - provider key map: `mindos.llm.provider-keys=openai:sk-xxx,local:dummy-key,qwen:sk-qwen`
  - retry controls: `mindos.llm.retry.max-attempts` (default `3`), `mindos.llm.retry.delay-ms` (default `300`)
  - Ark web search tool controls for `/responses` endpoints:
    - `mindos.llm.ark.web-search.enabled` (default `true`)
    - `mindos.llm.ark.web-search.stages` (comma-separated route stages such as `llm-fallback,skill-postprocess`; empty means no stage restriction)
    - `mindos.llm.ark.web-search.platforms` (comma-separated channel/platform names such as `im,dingtalk`; empty means no platform restriction)
  - short TTL response cache (optional):
    - `mindos.llm.cache.enabled` (default `false`)
    - `mindos.llm.cache.ttl-seconds` (default `60`)
    - `mindos.llm.cache.max-entries` (default `256`)
  - mainland model key map example: `mindos.llm.provider-keys=deepseek:sk-xxx,qwen:sk-yyy,kimi:sk-zzz,doubao:ark-aaa`
  - mainland aliases are supported (`dashscope/tongyi -> qwen`, `moonshot -> kimi`, `volcengine -> doubao`, `zhipu -> glm`, `baidu -> ernie`).
  - when endpoint map is empty, built-in mainland defaults are used for `deepseek/qwen/kimi/doubao/hunyuan/ernie/glm`.
  - per-request override: send `profile.llmProvider` (CLI: `profile set --llm-provider openai`; use `auto` to force automatic stage-based routing)
  - per-request/server-profile preset: send `profile.llmPreset` (CLI: `profile set --llm-preset quality`) to pick a named cost/quality preset before stage auto-routing.

### Production-ready multi-provider example

```properties
mindos.llm.http.enabled=true
mindos.llm.provider=qwen
mindos.llm.routing.mode=fixed
mindos.llm.routing.stage-map=llm-dsl:qwen,llm-fallback:qwen
mindos.llm.routing.preset-map=cost:qwen,balanced:qwen,quality:qwen
mindos.llm.provider-endpoints=openai:https://ai.2756online.com/openai/v1/chat/completions,gemini:https://ai.2756online.com/gemini/v1beta/models/gemini-2.0-flash:generateContent,qwen:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions,grok:https://ai.2756online.com/grok/v1/chat/completions
mindos.llm.provider-keys=deepseek:sk-xxx,openai:sk-yyy,gemini:AIzaSy-zzz,qwen:sk-qwen,grok:sk-aaa
```

If you prefer cleaner secret handling for Gemini, use the same endpoint without `?key=` and keep the Gemini secret only in `mindos.llm.provider-keys`.

### OpenRouter intent routing + Qwen/Doubao kept for free testing

Use OpenRouter for intent-based model selection while keeping Qwen/Doubao keys handy for cheap or free trials:

```properties
# Enable HTTP calls and intent-based routing
mindos.llm.http.enabled=true
mindos.llm.routing.mode=auto

# Intent providers (all through OpenRouter), Qwen/Doubao stay available for overrides
mindos.dispatcher.intent-routing.default-provider=gpt
mindos.dispatcher.intent-routing.code-provider=gpt
mindos.dispatcher.intent-routing.realtime-provider=grok
mindos.dispatcher.intent-routing.emotional-provider=gemini

# Intent + difficulty model picks (edit to your OpenRouter allowlist)
mindos.dispatcher.intent-routing.model.general.easy=gpt-4o-mini
mindos.dispatcher.intent-routing.model.general.hard=gpt-4o
mindos.dispatcher.intent-routing.model.code.medium=gpt-4o
mindos.dispatcher.intent-routing.model.realtime.medium=grok-beta
mindos.dispatcher.intent-routing.model.emotional.medium=gemini-2.0-flash

# Provider endpoints: OpenRouter for gpt/grok/gemini, native for qwen/doubao
mindos.llm.provider-endpoints=gpt:https://openrouter.ai/api/v1/chat/completions,grok:https://openrouter.ai/api/v1/chat/completions,gemini:https://openrouter.ai/api/v1/chat/completions,qwen:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions,doubao:https://ark.cn-beijing.volces.com/api/v3/chat/completions

# Provider models (Doubao needs a real Model ID or Endpoint ID)
mindos.llm.provider-models=qwen:qwen-plus,doubao:ep-xxxxxxxxxxxx

# Keys: one OpenRouter key feeds gpt/grok/gemini; keep Qwen/Doubao keys for low-cost testing
mindos.llm.provider-keys=gpt:${MINDOS_OPENROUTER_KEY},grok:${MINDOS_OPENROUTER_KEY},gemini:${MINDOS_OPENROUTER_KEY},qwen:${MINDOS_QWEN_KEY},doubao:${MINDOS_DOUBAO_ARK_KEY}
```

Tips:
- If you prefer a single OpenRouter key mapping for all three providers, keep the `provider-keys` map as shown; otherwise split by provider names OpenRouter exposes.
- Override `mindos.dispatcher.llm-fallback.provider` or `profile.llmProvider` to force Qwen/Doubao on-demand (e.g., for cost-sensitive sessions) without changing the intent-routing defaults.
- LLM call metrics (token estimate + multi-provider stats):
  - endpoint: `GET /api/metrics/llm?windowMinutes=60&provider=openai&includeRecent=true&recentLimit=20`
  - routing replay endpoint: `GET /api/metrics/llm/routing-replay?limit=200` (offline compare of `rule / preAnalyze / finalChannel` from recent real inputs)
  - toggles: `mindos.llm.metrics.enabled` (default `true`), `mindos.llm.metrics.max-recent-calls` (default `500`)
  - optional auth: `mindos.security.metrics.require-admin-token` (default `true`, validates `mindos.security.risky-ops.admin-token-header` / `mindos.security.risky-ops.admin-token`)
  - summary includes success/fallback rate, average latency, estimated token totals, provider aggregates, optional recent calls, and `securityAudit` writer metrics (`queueDepth`, `enqueuedCount`, `writtenCount`, `callerRunsFallbackCount`, `flushTimeoutCount`, `flushErrorCount`).
  - summary also includes:
    - `llmCache`: short TTL response cache status and effectiveness (`enabled`, `hitCount`, `missCount`, `hitRate`, `entryCount`, `ttlSeconds`, `maxEntries`)
    - `memoryWriteGate`: secondary semantic-duplicate write gate effectiveness (`secondaryDuplicateGateEnabled`, `secondaryDuplicateChecks`, `secondaryDuplicateIntercepted`, `secondaryDuplicateInterceptRate`)
    - `contextCompression`: dispatcher prompt-context compression effectiveness (`requests`, `compressedRequests`, `totalInputChars`, `totalOutputChars`, `avgCompressionRatio`, `summarizedTurns`)
    - `skillPreAnalyze`: dispatcher pre-analyze/guard stats (`mode`, `confidenceThreshold`, `requests`, `executed`, `accepted`, `skippedByGate`, `skippedBySkill`, `detectedSkillLoopSkipBlocked`, `skillTimeoutTriggered`)
    - `memoryHits`: prompt-memory retrieval hit stats (`requests`, `semanticHits`, `proceduralHits`, `rollupHits`, `approximateHitRate`)
    - `memoryContribution`: per-turn memory segment tags used for final reply construction (`requests`, `recentTagged`, `semanticTagged`, `proceduralTagged`, `personaTagged`, `rollupTagged`)
    - `llmCacheWindowHitRate`: cache hit rate within current `windowMinutes` only (better for release-over-release online effectiveness tracking)
    - `llmCacheWindowHits` / `llmCacheWindowMisses`: sample size of cache decisions inside current window, used together with `llmCacheWindowHitRate` to avoid small-sample misread.
    - `llmCacheWindowLowSample`: true when window sample size (`hits + misses`) is below threshold.
  - low-sample threshold config: `mindos.llm.metrics.cache.window-low-sample-threshold` (default `20`)
- Long-task orchestration API (multi-day / multi-worker):
  - create: `POST /api/tasks/{userId}` with `{title, objective, steps[], dueAt?, nextCheckAt?}`
  - list/query: `GET /api/tasks/{userId}?status=PENDING|RUNNING|BLOCKED|COMPLETED|CANCELLED`, `GET /api/tasks/{userId}/{taskId}`
  - claim ready work (supports parallel workers): `POST /api/tasks/{userId}/claim?workerId=worker-a&limit=2&leaseSeconds=300`
  - progress update: `POST /api/tasks/{userId}/{taskId}/progress` with `{workerId, completedStep?, note?, blockedReason?, nextCheckAt?, markCompleted}`
  - status override: `POST /api/tasks/{userId}/{taskId}/status` with `{status, note?, nextCheckAt?}`
  - manual auto-advance trigger: `POST /api/tasks/{userId}/auto-run` (advances one ready step per claimed task)
  - each claim uses a lease (`leaseOwner`, `leaseUntil`) so multiple worker threads/processes can collaborate safely without double-processing.
  - optional background auto-runner (disabled by default):
    - `mindos.tasks.auto-run.enabled`
    - `mindos.tasks.auto-run.fixed-delay-ms`
    - `mindos.tasks.auto-run.worker-id`
    - `mindos.tasks.auto-run.claim-limit`
    - `mindos.tasks.auto-run.lease-seconds`
    - `mindos.tasks.auto-run.next-check-delay-seconds`
- Scheduled news fetch + DingTalk push (disabled by default):
  - enable via `mindos.news.enabled=true`, configure RSS/JSON feeds with `mindos.news.sources` (comma-separated), cap items with `mindos.news.max-items`
  - example `mindos.news.sources` (可混合 CSV)：`https://feeds.bbci.co.uk/news/world/rss.xml,https://feeds.reuters.com/reuters/worldNews,https://rss.nytimes.com/services/xml/rss/nyt/World.xml,https://hnrss.org/frontpage`（RSS）；`https://api.spaceflightnewsapi.net/v4/articles/?limit=10&ordering=-published_at`（JSON）
  - schedule with `mindos.news.push.cron` and `mindos.news.push.timezone`; manual trigger `POST /api/news/push`
  - configure DingTalk destination: `mindos.news.push.dingtalk.session-webhook` (preferred for robot webhook) or `mindos.news.push.dingtalk.open-conversation-id` + `mindos.news.push.dingtalk.sender-id` (uses OpenAPI client)
  - summaries: after抓取最新源内容，服务会用 LLM 生成中文要点（3-6 条）并附上精选链接，LLM 不可用时自动降级为标题列表，长度受 `mindos.news.message.max-chars` 限制
  - HTTP/超时：`mindos.news.http.connect-timeout-ms`、`mindos.news.http.request-timeout-ms` 可调，异常会跳过该源继续抓取
  - admin token guard toggle: `mindos.news.require-admin-token` (default `true`, uses `X-MindOS-Admin-Token`)
  - observe/update runtime config: `GET /api/news/status`, `POST /api/news/config`
- Semantic memory can be stored explicitly with `remember ...`, `remember task: ...`, `记住：...`, or `记住任务：...`; explicit bucket prefixes such as `task/learning/eq/coding` override automatic bucket inference.
- Dispatcher habit-routing confidence controls (optional, app/JVM properties):
  - `mindos.dispatcher.habit-routing.enabled` (default `true`)
  - `mindos.dispatcher.habit-routing.min-total-count` (default `2`)
  - `mindos.dispatcher.habit-routing.min-success-rate` (default `0.6`)
  - `mindos.dispatcher.habit-routing.recent-window-size` (default `6`)
  - `mindos.dispatcher.habit-routing.recent-min-success-count` (default `2`)
  - `mindos.dispatcher.habit-routing.recent-success-max-age-hours` (default `72`)
  - continuation auto-routing now requires leading continuation cues (like `继续/按之前`) and stable recent success history.
- Dispatcher LLM skill-routing optimization controls:
  - `mindos.dispatcher.skill-routing.llm-shortlist-max-skills` (default `8`): only the top candidate skills are exposed to the LLM router prompt, reducing false positives and token cost when the skill catalog grows.
  - `mindos.dispatcher.skill-routing.conversational-bypass.enabled` (default `true`): short small-talk inputs such as `谢谢/好的/hello` skip the LLM skill-selection pass and go straight to normal chat fallback.
  - `mindos.dispatcher.realtime-intent.bypass.enabled` (default `true`): weather/news/market-like realtime intents skip skill pre-analyze and go directly to `llm-fallback`, which can then cooperate with Ark `/responses` web search.
  - `mindos.dispatcher.realtime-intent.terms` (comma-separated, default includes `天气/新闻/热点/汇率/股价/路况/航班/比赛/实时/最新`): configurable realtime intent cues for the fast path.
  - `mindos.dispatcher.realtime-intent.memory-shrink.enabled` (default `true`): when realtime intents land on `llm-fallback`, suppresses regular memory sections to avoid stale topic contamination.
  - `mindos.dispatcher.realtime-intent.memory-shrink.max-chars` (default `280`): cap for the shrunken realtime fallback prompt context budget.
  - `mindos.dispatcher.realtime-intent.memory-shrink.include-persona` (default `true`): keeps only persona snapshot in the shrunken realtime fallback context (other memory segments stay suppressed).
  - `mindos.dispatcher.skill.pre-analyze.mode=auto|always|never` (default `auto`): controls whether low-certainty requests go through LLM skill pre-analysis.
  - `mindos.dispatcher.skill.pre-analyze.confidence-threshold` (default `0`): in `auto` mode, skip pre-analysis when best candidate confidence is below this threshold.
  - `mindos.dispatcher.skill.pre-analyze.skip-skills` (default `time`): skill names that should not be selected by LLM pre-analysis.
  - intent + difficulty model routing for `llm-fallback` (optional, especially useful for OpenRouter multi-model setups):
    - `mindos.dispatcher.intent-routing.enabled` (default `true`)
    - `mindos.dispatcher.intent-routing.default-provider` / `code-provider` / `realtime-provider` / `emotional-provider`
    - model tiers by intent and difficulty:
      - `mindos.dispatcher.intent-routing.model.general.easy|medium|hard`
      - `mindos.dispatcher.intent-routing.model.code.easy|medium|hard`
      - `mindos.dispatcher.intent-routing.model.realtime.easy|medium|hard`
      - `mindos.dispatcher.intent-routing.model.emotional.easy|medium|hard`
    - emotional intent cue terms: `mindos.dispatcher.intent-routing.emotional-terms`
    - hard-input threshold: `mindos.dispatcher.intent-routing.hard-input-length-threshold` (default `180`)
  - `mindos.dispatcher.llm-dsl.provider` / `mindos.dispatcher.llm-dsl.preset` (default empty): stage-specific provider/preset defaults for `llm-dsl` routing calls when profile override is absent.
  - `mindos.dispatcher.llm-fallback.provider` / `mindos.dispatcher.llm-fallback.preset` (default empty): stage-specific provider/preset defaults for normal chat fallback when profile override is absent.
  - `mindos.dispatcher.skill.finalize-with-llm.enabled` (default `false` in base profile, `true` in `solo`): runs an LLM postprocess stage to convert structured skill output into a concise user-facing conclusion.
  - `mindos.dispatcher.skill.finalize-with-llm.skills` (default `teaching.plan,todo.create,eq.coach,code.generate,file.search,mcp.*`): comma-separated skill allowlist for postprocess finalization; supports wildcard prefixes such as `mcp.*`.
  - `mindos.dispatcher.skill.finalize-with-llm.max-output-chars` (default `900`): hard cap for the final postprocessed skill reply.
  - `mindos.dispatcher.skill.finalize-with-llm.provider` (default empty): optional dedicated provider override for `skill-postprocess` stage.
  - `mindos.dispatcher.skill.finalize-with-llm.preset` (default empty in base profile, `cost` in `solo`): optional dedicated preset override for `skill-postprocess` stage.
  - `mindos.dispatcher.routing-replay.max-samples` (default `200`): retained sample size for routing replay offline analysis.
- Optional post-skill summary writeback controls:
  - `mindos.memory.post-skill-summary.enabled` (default `false`): when enabled, successful skill outputs are summarized and written to semantic memory.
  - `mindos.memory.post-skill-summary.skills` (default `teaching.plan,todo.create,eq.coach,code.generate,file.search`): comma-separated skill allowlist for summary writeback.
  - `mindos.memory.post-skill-summary.max-reply-chars` (default `280`): summary-safe cap for skill output included in memory writeback.
  - chat `executionTrace.routing` now includes `route`, `selectedSkill`, `confidence`, `reasons`, and `rejectedReasons` for diagnosing why a skill was chosen or why the request fell back to plain LLM chat.
- Dispatcher token/loop guards:
  - `mindos.dispatcher.prompt.max-chars` (default `2800`)
  - `mindos.dispatcher.memory-context.max-chars` (default `1800`)
  - `mindos.dispatcher.memory-context.keep-recent-turns` (default `2`): keep the last N raw turns verbatim in prompt context.
  - `mindos.dispatcher.memory-context.history-summary-min-turns` (default `4`): once recent conversation reaches this threshold, older turns are compressed into a short review summary before entering the prompt.
  - dispatcher also sends a provider-agnostic `chatHistory` snapshot (last few turns with timestamps) along with the prompt, so switching `llmProvider` keeps the same shared conversation context.
  - `llm.orchestrate` skill共享上述 `memoryContext` + `chatHistory` 做多 provider 编排与降级；可调参数：`mindos.llm.orchestrate.providers`（默认 `openai,deepseek,qwen`），`mindos.llm.orchestrate.max-hops`（默认 `2`），`mindos.llm.orchestrate.prompt.max-chars`（默认 `1600`），`mindos.llm.orchestrate.history.max-items`（默认 `6`）。
  - `mindos.dispatcher.llm-reply.max-chars` (default `1200`)
  - `mindos.dispatcher.skill.guard.max-consecutive` (default `2`), blocks repeated same-skill loop routing and falls back to broader reasoning.
  - `mindos.dispatcher.skill.guard.recent-window-size` (default `6`), recent procedural entries scanned for loop fingerprints.
  - `mindos.dispatcher.skill.guard.repeat-input-threshold` (default `2`), repeated same-skill + same-input fingerprints allowed within cooldown before blocking.
  - `mindos.dispatcher.skill.guard.cooldown-seconds` (default `180`), cooldown window for repeated same-input loop detection.
  - `mindos.dispatcher.skill.guard.pre-execute-heavy.enabled` (default `true`), run loop guard before execution for configured heavy skills on explicit/rule/habit/llm-dsl routes.
  - `mindos.dispatcher.skill.guard.pre-execute-heavy.skills` (default `eq.coach,teaching.plan,todo.create,code.generate,file.search,mcp.*`), heavy skill exact names/prefixes covered by pre-execute loop guard.
  - `mindos.dispatcher.skill.timeout.eq-coach-im-ms` (default `12000`), IM-only timeout guard for `eq.coach` execution to avoid very long silent waits.
  - `mindos.dispatcher.skill.timeout.eq-coach-im-reply` (default short Chinese fallback), timeout reply text returned when the IM `eq.coach` guard triggers.
- Prompt-injection safety guard (default enabled):
  - `mindos.dispatcher.prompt-injection.guard.enabled` (default `true`)
  - `mindos.dispatcher.prompt-injection.guard.risk-terms` (comma-separated risky phrases)
  - `mindos.dispatcher.prompt-injection.guard.safe-reply` (safe response when risky prompt is detected)
  - when matched, dispatcher returns channel `security.guard` and refuses sensitive execution.
- High-risk operation approval policy (optional, server-side enforced):
  - `mindos.security.risky-ops.require-approval` (default `false`)
  - `mindos.security.risky-ops.use-challenge-token` (default `true`), enable one-time challenge token approval to prevent header replay
  - `mindos.security.risky-ops.approval-header` / `mindos.security.risky-ops.approval-value`
  - `mindos.security.risky-ops.challenge-header` / `mindos.security.risky-ops.challenge.max-ttl-seconds`
  - `mindos.security.risky-ops.admin-token-header` / `mindos.security.risky-ops.admin-token` (optional extra secret)
  - challenge endpoint: `POST /api/security/challenge` with `{operation, resource, actor, ttlSeconds?}` (requires admin token when configured)
  - challenge validation is strict: token must match `operation + resource + actor + request IP`, and token is consumed once.
  - `mindos.security.skill.load-jar.allowed-hosts` / `mindos.security.skill.load-mcp.allowed-hosts`
  - when enabled, `POST /api/skills/load-jar`, `POST /api/skills/load-mcp`, and `POST /api/tasks/{userId}/auto-run` require approval headers and skill URLs are host allowlisted.
- Skill capability whitelist policy (fine-grained):
  - `mindos.security.skill.capability.guard.enabled` (default `true`)
  - `mindos.security.skill.allowed-capabilities` (default `fs.read,fs.write,exec,net`)
  - `mindos.security.skill.capability-map` (example: `echo:exec,file.search:fs.read,mcp.docs.searchDocs:net`)
  - blocked skill execution returns channel `security.guard` with missing capability details.
- Structured security audit logging:
  - `mindos.security.audit.enabled` (default `true`)
  - `mindos.security.audit.file` (default `logs/security-audit.log`)
  - `mindos.security.audit.trace-id-header` (default `X-Trace-Id`)
  - audit query endpoints:
    - `GET /api/security/audit?limit=50` (simple recent list, requires admin token when configured)
    - `GET /api/security/audit/write-metrics` (writer queue + fallback/flush counters for observability)
    - `GET /api/security/audit/query?limit=50&cursor=<signed_cursor>&actor=&operation=&result=&traceId=&from=&to=` (filtered cursor paging)
      - `cursor` is short JWT-style (`header.payload.signature`), HMAC-signed (tamper-proof), and bound to current filters.
      - JWT header includes `kid` for signing key version so key rotation can verify old cursors while new cursors use the active key.
      - `from` / `to` use ISO-8601 UTC timestamps (example: `2026-03-23T10:00:00Z`).
      - `nextCursor` and `nextCursorExpiresAt` are returned when more items exist.
      - `cursorKeyVersion` returns the key version parsed from the current request cursor (`kid` for JWT cursors, legacy markers for old cursor formats).
      - `cursorType` returns the current request cursor format: `none|jwt|legacy-numeric|legacy-signature`.
    - writer metrics fields include `queueDepth`, `queueRemainingCapacity`, `enqueuedCount`, `writtenCount`, `callerRunsFallbackCount`, `flushTimeoutCount`, and `flushErrorCount`.
  - cursor key rotation config:
    - `mindos.security.audit.cursor-active-key-version` (example: `v2`)
    - `mindos.security.audit.cursor-signing-keys` (example: `v1:old-secret,v2:new-secret`)
    - if keyring is empty, fallback key `mindos.security.audit.cursor-signing-key` is used for active version.
  - storage partition toggle: `mindos.security.audit.daily-partition-enabled` (default `true`, writes to daily files like `security-audit-2026-03-25.log` and enables window-based file pruning during queries).
  - query optimization toggle: `mindos.security.audit.query.assume-chronological-order` (default `true`, allows early stop when `to` is set and log timestamps have passed upper bound).
  - query guardrail: `mindos.security.audit.query.max-scanned-files` (default `400`, caps partition files scanned per request to avoid unbounded historical scans).
  - audit write pipeline tuning:
    - `mindos.security.audit.write-queue-capacity` (default `2048`)
    - `mindos.security.audit.write-flush-timeout-ms` (default `2000`)
    - `mindos.security.audit.write-flush-warning-interval-ms` (default `60000`, throttles repeated flush warning logs)
  - audit fields include actor, operation, resource, timestamp, traceId, result, reason, remote address, and user-agent.
- Persona learning safety controls (optional, app/JVM properties):
  - `mindos.dispatcher.persona-core.enabled` (default `true`)
  - `mindos.dispatcher.persona-core.preferred-channel.min-consecutive-success` (default `2`)
  - `mindos.dispatcher.persona-core.ignored-profile-terms` (default `unknown,null,n/a,na,tbd,todo,随便,不知道,待定`)
  - `mindos.memory.preference.overwrite-confirm-turns` (default `2`): conflicting long-term profile values need repeated confirmation turns before replacement.
  - Default persona fallback (used when no profile has been learned yet):
    - `mindos.memory.preference.default.assistant-name` (default `MindOS`)
    - `mindos.memory.preference.default.role` (default `personal-assistant`)
    - `mindos.memory.preference.default.style` (default `warm`)
    - `mindos.memory.preference.default.language` (default `zh-CN`)
    - `mindos.memory.preference.default.timezone` (default `Asia/Shanghai`)
    - `mindos.memory.preference.default.preferred-channel` (default empty)
- Memory sync API supports incremental pull via cursor (`since`) for multi-terminal synchronization.
- Learned persona profile can be inspected via `GET /api/memory/{userId}/persona` (CLI: `mindos profile persona show`).
- Persona debug explain view is available at `GET /api/memory/{userId}/persona/explain` to inspect pending conflict overrides before confirmation.
- Memory compression planning supports gradual stages (`RAW -> CONDENSED -> BRIEF -> STYLED`) with per-user style profile via `POST /api/memory/{userId}/style`, `GET /api/memory/{userId}/style`, and `POST /api/memory/{userId}/compress-plan`.
- Compression plan supports optional `focus` (`learning`/`task`/`review`) and style update supports optional auto-tune (`POST /api/memory/{userId}/style?autoTune=true&sampleText=...`).
- Memory key-signal detection is configurable via JVM properties (comma-separated):
  - `mindos.memory.key-signal.constraint-terms`
  - `mindos.memory.key-signal.deadline-terms`
  - `mindos.memory.key-signal.contact-terms`
  - examples: `-Dmindos.memory.key-signal.constraint-terms=必须,禁止,不要,不可`.
- Semantic memory anti-pollution controls (optional, JVM properties):
  - write gate toggle: `mindos.memory.write-gate.enabled` (default `false`)
  - write gate min length: `mindos.memory.write-gate.min-length` (default `10`)
  - bucket min length override: `mindos.memory.write-gate.min-length.<bucket>` (optional, falls back to global min length)
  - secondary semantic-duplicate gate toggle: `mindos.memory.write-gate.semantic-duplicate.enabled` (default `false`)
  - secondary semantic-duplicate token threshold: `mindos.memory.write-gate.semantic-duplicate.threshold` (default `0.82`, range `0..1`)
  - search recency decay half-life hours: `mindos.memory.search.decay-half-life-hours` (default `72`)
  - two-stage retrieval coarse candidate floor: `mindos.memory.search.coarse.min-candidates` (default `128`)
  - two-stage retrieval coarse candidate multiplier: `mindos.memory.search.coarse.multiplier` (default `8`, final coarse cap = `max(min-candidates, limit*multiplier)`)
  - explicit preferred-bucket search cross-bucket fallback cap: `mindos.memory.search.cross-bucket.max` (default `2`)
  - explicit preferred-bucket search cross-bucket fallback ratio: `mindos.memory.search.cross-bucket.ratio` (default `0.5`, range `0..1`)
  - optional hybrid sparse+dense retrieval: `mindos.memory.search.hybrid.enabled` (default `false`), `mindos.memory.search.hybrid.lexical-weight` (default `0.55`), `mindos.memory.search.hybrid.k1` (default `1.2`), `mindos.memory.search.hybrid.b` (default `0.75`)
  - optional local embedding generation for memory writes/query vectors: `mindos.memory.embedding.local.enabled` (default `false`), `mindos.memory.embedding.local.dimensions` (default `16`)
  - optional layered semantic ranking: `mindos.memory.layers.enabled` (default `false`), `mindos.memory.layers.buffer-hours` (default `6`), `mindos.memory.layers.working-hours` (default `72`), `mindos.memory.layers.fact-max-chars` (default `160`)
  - conversation rollup: `mindos.memory.conversation-rollup.enabled` (default `true`), `mindos.memory.conversation-rollup.threshold-turns` (default `24`), `mindos.memory.conversation-rollup.keep-recent-turns` (default `8`), `mindos.memory.conversation-rollup.min-turns` (default `6`)
  - precedence for `mindos.memory.*`: system properties (`-D`) > `application.properties` > built-in defaults.
  - when enabled, low-signal short semantic entries are skipped; retrieval prefers same inferred topic bucket and keeps bounded cross-bucket fallback when preferred bucket is explicit.
  - conversation rollup stores a semantic summary under bucket `conversation-rollup` once hot episodic turns exceed the threshold; older turns are then kept in the sync log while recent turns remain hot in local episodic memory for prompt construction.
- Memory sync performance regression test knobs (test-only JVM properties):
  - `mindos.memory.sync.perf-baseline-ms` (default `4000`)
  - `mindos.memory.sync.perf-retries` (default `1`, CI can raise to `2` on noisy runners)
  - example: `./mvnw -q -pl assistant-memory -am test -Dtest=MemorySyncServiceTest#shouldMeetBasicSyncPerformanceBaseline -Dsurefire.failIfNoSpecifiedTests=false -Dmindos.memory.sync.perf-baseline-ms=5000 -Dmindos.memory.sync.perf-retries=2`
- Memory NLU synonyms for style/compress intents are configurable via JVM system properties (`-Dmindos.memory.nlu.*`); values are comma-separated terms and normalize to canonical values used by API/CLI (`focus`: `task|learning|review`, `style`: `action|coach|story|concise`, `tone`: `warm|direct|neutral`, `format`: `bullet|plain`).
- `eq.coach` supports optional output controls: `style` (`gentle|direct|workplace|intimate`), `mode` (`analysis|reply|both`), `priorityFocus` (`p1|p2|p3`).
- `eq.coach` risk terms are configurable via JVM properties (comma-separated):
  - `mindos.eq.coach.risk.high-terms`
  - `mindos.eq.coach.risk.medium-terms`
  - example: `-Dmindos.eq.coach.risk.high-terms=离婚,分手,崩溃,绝望,失眠 -Dmindos.eq.coach.risk.medium-terms=冲突,冷战,争执,焦虑,拖延`
- MCP servers support both internal services and third-party services (for example, GitHub MCP) through `/api/skills/load-mcp`; optional `headers` can be provided for per-server authentication.
  - focus keys:
    - `mindos.memory.nlu.focus.task-terms`
    - `mindos.memory.nlu.focus.learning-terms`
    - `mindos.memory.nlu.focus.review-terms`
  - style keys:
    - `mindos.memory.nlu.style.action-terms`
    - `mindos.memory.nlu.style.coach-terms`
    - `mindos.memory.nlu.style.story-terms`
    - `mindos.memory.nlu.style.concise-terms`
  - tone keys:
    - `mindos.memory.nlu.tone.warm-terms`
    - `mindos.memory.nlu.tone.direct-terms`
    - `mindos.memory.nlu.tone.neutral-terms`
  - format keys:
    - `mindos.memory.nlu.format.bullet-terms`
    - `mindos.memory.nlu.format.plain-terms`
  - dev example:
    ```bash
    ./mvnw -pl assistant-api -am spring-boot:run \
      -Dspring-boot.run.jvmArguments="-Dfile.encoding=UTF-8 -Dmindos.memory.nlu.focus.review-terms=复盘,总结,retrospective -Dmindos.memory.nlu.style.action-terms=action,行动,行动派 -Dmindos.memory.nlu.tone.warm-terms=warm,温和,gentle -Dmindos.memory.nlu.format.bullet-terms=bullet,列表,markdown list"
    ```
  - prod example:
    ```bash
    java \
      -Dfile.encoding=UTF-8 \
      -Dmindos.memory.nlu.focus.review-terms="复盘,总结,retrospective" \
      -Dmindos.memory.nlu.style.action-terms="action,行动,行动派" \
      -Dmindos.memory.nlu.tone.warm-terms="warm,温和,gentle" \
      -Dmindos.memory.nlu.format.bullet-terms="bullet,列表,markdown list" \
      -jar assistant-api/target/assistant-api-0.1.0-SNAPSHOT.jar
    ```
  - quick verify phrases:
    - `按我的风格压缩这段记忆：记录今日复盘，聚焦到retrospective` -> focus `review`
    - `把记忆风格改成 行动派，语气 gentle，格式 markdown list` -> `action/warm/bullet`
- IM webhook integration (disabled by default) supports Feishu/DingTalk/WeChat text chat via `/api/im/feishu/events`, `/api/im/dingtalk/events`, `/api/im/wechat/events`; all platforms can enable signature verification independently in `application.properties`.
- DingTalk now supports an async reply path when the event payload includes `sessionWebhook`: webhook first returns a processing receipt with task ID, then the server finishes dispatch in background and pushes the final text result back to the same DingTalk session. Related properties: `mindos.im.dingtalk.async-reply.*`; `allow-insecure-localhost-http` is intended only for local tests/debugging.
- When a DingTalk async callback cannot be delivered (for example `sessionWebhook` expired or callback failed), the server can optionally try DingTalk OpenAPI proactive delivery first (`mindos.im.dingtalk.openapi-fallback.*`, requires the robot/app to have the corresponding DingTalk send-message permission plus `appKey/appSecret/robotCode`). In this repo the DingTalk integration is conversation-oriented (webhook payloads revolve around `conversationId` / `openConversationId` + chat replies), so the default fallback preference is `conversation-first`; if a tenant instead behaves more like direct user notification, `mindos.im.dingtalk.openapi-fallback.preferred-send-mode=user-first` can switch the primary attempt to batch user send. If OpenAPI fallback is unavailable or still fails, the final result is retained in the long-task record and can be recovered in chat with natural-language commands like `查进度` / `查看结果`, and the next ordinary DingTalk message will also carry a compensation notice with the missed result.
- DingTalk can also run in robot stream mode. In that setup, DingTalk pushes messages over the long-lived stream connection instead of the `/api/im/dingtalk/events` webhook, and MindOS proactively sends a short “请稍等”状态 before the final reply when processing is slow。
- IM 文本可直接触发 memory 能力：`查看记忆风格`、`按任务聚焦压缩这段记忆：...`、`根据这段话微调记忆风格：...`。
- 若压缩结果提示“关键约束可能被弱化”，可直接回复“要/好的/ok”继续获取原文关键点清单并逐条复核（IM 与 CLI 交互窗口均支持）；复核后回复“生成待办”可一键转成按 `today / this week / later` 分组的执行清单。
- 生成待办时会同步显示“当前待办策略”预览（阈值与建议时段），方便在对话里确认当前生效配置。
- 待办优先级策略可通过 JVM 系统属性覆盖（默认不配即可）。示例（`application.properties` 风格）：
  ```properties
  # Priority thresholds
  mindos.todo.priority.p1-threshold=45
  mindos.todo.priority.p2-threshold=25

  # Suggested completion windows
  mindos.todo.window.p1=建议24小时内完成
  mindos.todo.window.p2=建议3天内完成
  mindos.todo.window.p3=建议本周内完成

  # Legend shown in IM/CLI todo checklist
  mindos.todo.legend=优先级说明：P1=今天必须完成，P2=3天内推进，P3=本周内安排。
  ```
  自定义示例：
  ```properties
  mindos.todo.priority.p1-threshold=60
  mindos.todo.priority.p2-threshold=30
  mindos.todo.window.p2=建议两天内完成
  mindos.todo.legend=优先级说明：按团队自定义策略执行。
  ```
- CLI 支持会话内临时策略覆盖（不修改全局配置）：
  - `/todo policy show`
  - `/todo policy set --p1-threshold 70 --p2-threshold 20 --window-p2 建议两天内推进 --legend 优先级说明：会话策略`
  - `/todo policy reset`
- IM/CLI 的 memory compress 回复会附带轻量可观测信息（如压缩率与关键约束保留提示），便于在自然语言对话里确认压缩效果。
- `POST /api/memory/{userId}/sync` response now includes apply counters: `deduplicatedCount`, `keySignalInputCount`, and `keySignalStoredCount` for monitoring compression/dedup effectiveness.
- **Custom skills (JSON)**: drop `.json` files into `mindos.skills.custom-dir`; reload without restart via `POST /api/skills/reload`.
  ```json
  { "name": "greet", "description": "Warm greeting", "triggers": ["greet","hello"], "response": "Hello {{user}}! You said: {{input}}" }
  ```
  Set `"response": "llm"` to route the input to the configured LLM instead.
- **External skill JARs**: set `mindos.skills.external-jars=https://host/skill.jar` (comma-separated). JARs must implement `Skill` and declare it in `META-INF/services/com.zhongbo.mindos.assistant.skill.Skill`. Load a JAR at runtime via `POST /api/skills/load-jar {"url":"..."}`.
- **MCP skills**: set `mindos.skills.mcp-servers=docs:http://localhost:8081/mcp,search:https://example.com/mcp`. Each remote MCP tool is exposed as a namespaced skill like `mcp.docs.searchDocs`. Dispatcher auto-detection can route natural requests such as `search docs for auth guide` to matching MCP tools, and explicit DSL can target the full skill name. Reload all configured MCP servers via `POST /api/skills/reload-mcp`, or attach one server at runtime via `POST /api/skills/load-mcp {"alias":"docs","url":"http://localhost:8081/mcp"}`.
  - 为无联网能力的模型补“上网”能力：准备带搜索工具的 MCP 服务器（如 `search` 别名），配置 `mindos.skills.mcp-servers=search:https://your-mcp-server/mcp` 后会自动注册 `mcp.search.<tool>`，即可在对话中调用搜索类技能。
  - Brave Search 可用专用配置快速启用（会自动并入 `mcp-servers`）：`mindos.skills.mcp.brave.enabled=true`、`mindos.skills.mcp.brave.url=...`、`mindos.skills.mcp.brave.api-key=...`，默认请求头为 `X-Subscription-Token`（可通过 `mindos.skills.mcp.brave.api-key-header` 覆盖），默认别名为 `brave`（可通过 `mindos.skills.mcp.brave.alias` 覆盖）。当 URL 指向 Brave 官方 REST 搜索接口（如 `/res/v1/web/search`）时，会自动注册为 `mcp.<alias>.webSearch` 并复用现有自然语言路由/LLM 总结链路。
  - MCP 服务器鉴权/私有 API：用 `mindos.skills.mcp-server-headers` 配置每个别名的请求头（逗号分隔多个别名，分号分隔头；示例：`docs:Authorization=Bearer%20token;X-API-KEY=abc123,search:Authorization=Bearer%20another`）。对应别名的所有 MCP 调用都会附带这些头，适合传递 API key。
  - 对话/自然语言添加 MCP（含 API key）：可以在聊天里说“添加一个 search MCP，地址 https://your-mcp-server/mcp，Authorization=Bearer xxx”，由上层映射为 `POST /api/skills/load-mcp`：
    ```json
    {
      "alias": "search",
      "url": "https://your-mcp-server/mcp",
      "headers": {
        "Authorization": "Bearer xxx",
        "X-API-KEY": "abc123"
      }
    }
    ```
- `code.generate` can be pinned to a provider and difficulty-tier models via:
  - `mindos.skill.code-generate.llm-provider`
  - `mindos.skill.code-generate.model.easy|medium|hard`
- Skill management API: `GET /api/skills` lists all registered skills.
- Chat-style skill discovery is supported for everyday phrasing such as `你有哪些技能？`, `你能做什么？`, and `你还可以学习哪些技能？`.

## Fast validation sequence
```bash
./mvnw -q -pl assistant-sdk,mindos-cli -am test
./mvnw -q -pl assistant-api -am test -Dtest=MemorySyncControllerTest
./mvnw -q test
```
