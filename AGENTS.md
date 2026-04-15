# AGENTS.md

## Purpose
This repository is a lightweight, single-user personal AI assistant backend. Optimize for small, safe changes that keep `assistant-api` bootable and the reactor test suite passing.

## Existing AI instruction sources
- Glob scan for `**/{.github/copilot-instructions.md,AGENT.md,AGENTS.md,CLAUDE.md,.cursorrules,.windsurfrules,.clinerules,.cursor/rules/**,.windsurf/rules/**,.clinerules/**,README.md}` found `AGENTS.md`, root `README.md`, and `assistant-memory/src/main/resources/models/bge-micro/README.md`.
- Treat this file as primary coding-agent guidance.

## Architecture snapshot
- Root `pom.xml` is an aggregator (`packaging=pom`) using Java `17` and Spring Boot `3.3.10`.
- Modules and boundaries:
  - `assistant-api`: REST entrypoint and Spring Boot app
  - `assistant-dispatcher`: routes user input to skills/LLM
  - `assistant-memory`: episodic, semantic, procedural memory services (central memory defaults to file-backed storage under `data/memory-sync` when enabled; falls back to in-memory on file-repo init failure; JDBC central repository auto-enabled when a `DataSource` exists)
  - `assistant-skill`: skill interface, registry, DSL execution, example skills; `loader/` sub-package for custom JSON skills and external JAR plugins; `mcp/` sub-package for MCP tool adapters over HTTP JSON-RPC; `cloudapi/` sub-package for semantic cloud API skill adapters
  - `assistant-llm`: API-key-based LLM client adapter (supports global/per-user keys plus provider-specific keys/endpoints; stubbed response when key missing)
  - `assistant-common`: shared contracts (`SkillDsl`, `SkillContext`, `SkillResult`, `LlmClient`)
  - `assistant-sdk`: Java SDK for client-side server calls
  - `mindos-cli`: Picocli command-line client using `assistant-sdk`; default entrypoint opens an interactive chat session and slash commands handle common session/profile/memory actions in one window
- Runtime flow: `/chat` (backward compatible with `/api/chat`) -> dispatcher -> (DSL/skill engine or LLM fallback) -> memory updates.
- IM integration flow (optional): `/api/im/feishu/events`, `/api/im/dingtalk/events`, `/api/im/wechat/events` -> dispatcher chat route -> platform text response; DingTalk can also acknowledge immediately and push the final reply later through event `sessionWebhook` when `mindos.im.dingtalk.async-reply.*` is enabled (`allow-insecure-localhost-http` should stay local-test only). If callback delivery fails, the server may optionally try DingTalk OpenAPI proactive delivery via `mindos.im.dingtalk.openapi-fallback.*`; because the current DingTalk integration is conversation-oriented, `conversation-first` is the intended default send mode and `openConversationId` should be preferred when the event provides it. Users can still query missed async tasks in-chat with `查进度` / `查看结果`, and the next DingTalk message will include a compensation notice for any retained results.
- IM 文本也支持 memory 自然语言入口：如“查看记忆风格”“按任务聚焦压缩这段记忆：...”“根据这段话微调记忆风格：...”。
- Multi-terminal memory flow: `/api/memory/{userId}/sync` with cursor-based pull (`GET`) and idempotent push (`POST eventId`).
- Prompt memory retrieval preview flow: `/api/memory/{userId}/retrieve-preview` (`GET`) builds prompt-side memory context for a query; optional admin-token protection is controlled by `mindos.security.memory.retrieve-preview.require-admin-token` together with the shared risky-ops admin token/header settings.
- Long-task orchestration flow: `/api/tasks/{userId}` for create/list/detail + `/api/tasks/{userId}/claim` for lease-based worker claiming + `/api/tasks/{userId}/{taskId}/progress|status` for multi-day progress updates.
- Long-task auto-advancer: `/api/tasks/{userId}/auto-run` manual trigger plus optional background scheduler via `mindos.tasks.auto-run.*` properties.
- Persona inspection flow: `/api/memory/{userId}/persona` (`GET`) returns the confirmed long-term persona profile learned for that user.
- Persona explain flow: `/api/memory/{userId}/persona/explain` (`GET`) returns confirmed profile plus pending override candidates for debug visibility.
- LLM metrics flow: `/api/metrics/llm` (`GET`) returns windowed call stats (provider aggregates, success/fallback rate, latency, estimated token usage, optional recent calls) plus `securityAudit` writer summary (`queueDepth`, `enqueuedCount`, `writtenCount`, fallback/flush counters).
- News push flow: `/api/news/status|config|push` for scheduled/manual news delivery controls and runtime status.
- DingTalk observability flow: `/api/im/dingtalk/token-monitor|outbound-debug|stream-stats` for token, outbound-channel, and stream-update diagnostics.
- LLM auto-routing supports optional stage mapping and preset mapping via `mindos.llm.routing.mode`, `mindos.llm.routing.stage-map`, and `mindos.llm.routing.preset-map`; fallback-stage selection can also auto-route by detected intent/difficulty via `mindos.dispatcher.intent-routing.*`; per-request `profile.llmProvider` / `profile.llmPreset` can still override one request.
- Semantic analysis can run in local-first mode via `mindos.dispatcher.semantic-analysis.*`; local model downgrade/escalation policy is configurable via `mindos.dispatcher.local-escalation.*`; semantic clarify gating is controlled by `mindos.dispatcher.semantic-analysis.clarify-min-confidence` (default `0.70`).
- Dispatcher semantic clarification validates required params against the effective payload after memory/default completion (not only raw semantic payload); continuation inputs still bypass semantic-clarify routing.

### 本地调度 / 云端总结（规范版摘要）
- 本地模型只负责“判定要做什么”：意图识别、槽位补全、澄清判断、技能选择；云端模型只负责“怎么说”：最终回复、总结、润色。
- 默认遵循 local-first：本地优先，云端仅在回退、增强或高质量总结时介入。
- 本地 Ollama 端点与模型名必须通过 provider map 配置，禁止在代码里硬编码。
- 路由分析输出必须是严格 JSON；不得直接生成最终回复，不得补造缺失参数，只能从白名单技能中选择。
- 低置信度或补全后仍缺必填字段时，必须进入 `semantic.clarify`；续写类输入跳过澄清路由。
- 路由前按 `Persona -> Semantic -> Procedural -> Recent Episodic -> Current Input` 读取记忆，优先使用已确认信息。
- 记忆写入要分层：Persona 存稳定偏好，Semantic 存确认事实，Episodic 存近期摘要，Procedural 存路由经验。
- 本地模型不可用时应先修复 Ollama/本地端点，再观察 MindOS 路由；不要把“已路由到 local”误当成“本地模型可用”。

  - Local semantic analysis (Ollama / Gemma3)
    - The dispatcher and `SemanticAnalysisService` support a local-first semantic analyzer backed by a local model (examples in logs show `http://localhost:11434/api/chat` with `gemma3:1b-it-q4_K_M`). To enable locally-hosted Ollama + Gemma3, set provider maps and the semantic-analysis properties (examples below).
    - Recommended environment / secrets (examples placed in `dist/mindos-windows-server/mindos-secrets.properties`):
      - MINDOS_LLM_PROVIDER_ENDPOINTS=local:http://localhost:11434/api/chat,qwen:https://<cloud>
      - MINDOS_LLM_PROVIDER_MODELS=local:gemma3:1b-it-q4_K_M,qwen:qwen3.6-plus
      - MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_LLM_ENABLED=true
      - MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_FORCE_LOCAL=true
      - MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_LOCAL_ESCALATION_ENABLED=true
      - MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_LOCAL_ESCALATION_CLOUD_PROVIDER=qwen
    - Common local endpoint used in this repo: `http://localhost:11434/api/chat` (the codebase logs and tests use this URL). Pull / serve the quantized Gemma model via your Ollama / local runtime and confirm API readiness before running the server.
    - Two recommended Gemma3 presets for i5 + 8GB (quantized q4_K_M model chosen to fit memory):
      - "stable" (bias toward reliability / lower memory usage):
        - model: `gemma3:1b-it-q4_K_M`
        - llm-complexity.max-tokens: 120
        - dispatcher local resource guard: `mindos.dispatcher.local-escalation.resource-guard.min-free-memory-mb=512`
        - threads: 1-2 (configure via runtime/ollama runner)
        - local preset mapping: `mindos.llm.routing.preset-map=local:stable`
      - "quality" (bias toward slightly higher generation quality when resources permit):
        - model: same quantized model but raise token/prompt budgets: `mindos.dispatcher.semantic-analysis.max-tokens=220`, `mindos.dispatcher.llm-fallback.max-tokens=420`
        - resource guard: min-free-memory-mb=768 (use only on machines with >8GB free headroom)
        - threads: 2 (if available)
    - Keep models configurable via `MINDOS_LLM_PROVIDER_MODELS` and per-request `profile.llmProvider` / `profile.llmPreset` — avoid hard-coding model names in code.
- Memory compression planning flow: `/api/memory/{userId}/style` (`GET`/`POST`) + `/api/memory/{userId}/compress-plan` (`POST`) for gradual compression with per-user style profile.
- `compress-plan` 可选 `focus`（learning/task/review）；`style` 更新可选 `autoTune=true&sampleText=...` 做轻量风格微调。
- MemoryIntentNlu 的 focus/style/tone/format 同义词支持通过系统属性配置（`mindos.memory.nlu.*-terms`，逗号分隔）；若新增或调整键名/默认词，需同步更新 `README.md` 示例。
- MemoryConsolidationService 的 key-signal 词表支持系统属性配置（`mindos.memory.key-signal.*-terms`，逗号分隔）；若新增或调整键名/默认词，需同步更新 `README.md` 示例。
- 语义记忆防污染支持可选系统属性：`mindos.memory.write-gate.enabled`、`mindos.memory.write-gate.min-length`、`mindos.memory.search.decay-half-life-hours`；若调整键名/默认值，需同步更新 `README.md`。
- `eq.coach` 风险词支持系统属性覆盖（`mindos.eq.coach.risk.high-terms`、`mindos.eq.coach.risk.medium-terms`，逗号分隔）。

## Repository map
- `assistant-api/src/main/java/com/zhongbo/mindos/assistant/api/ChatController.java`: chat HTTP interface.
- `assistant-api/src/main/java/com/zhongbo/mindos/assistant/api/MemorySyncController.java`: memory sync/retrieve-preview/style/compress-plan/persona APIs.
- `assistant-api/src/main/java/com/zhongbo/mindos/assistant/api/SkillController.java`: lists/reloads custom skills and loads external JAR skills.
- `assistant-api/src/main/java/com/zhongbo/mindos/assistant/api/news/NewsController.java`: scheduled news push status/config/manual trigger APIs.
- `assistant-api/src/main/java/com/zhongbo/mindos/assistant/api/LongTaskController.java`: long-task APIs for multi-worker claiming and progress updates.
- `assistant-api/src/main/java/com/zhongbo/mindos/assistant/api/LlmMetricsController.java`: LLM metrics API and recent-call window query.
- `assistant-api/src/main/java/com/zhongbo/mindos/assistant/api/SecurityChallengeController.java`: challenge issuing + security audit query/read APIs.
- `assistant-api/src/main/java/com/zhongbo/mindos/assistant/api/im/ImWebhookController.java`: Feishu/DingTalk/WeChat webhook adapter entrypoints.
- `assistant-api/src/main/java/com/zhongbo/mindos/assistant/api/im/DingtalkMonitorController.java`: DingTalk token monitor/outbound debug/stream stats APIs.
- `assistant-skill/src/main/java/com/zhongbo/mindos/assistant/skill/mcp/McpSkillLoader.java`: maps configured MCP servers to namespaced skills (`mcp.<alias>.<tool>`).
- `assistant-skill/src/main/java/com/zhongbo/mindos/assistant/skill/examples/NewsSearchSkill.java`: built-in `news_search` skill (Google RSS + 36kr aggregation, cache, local LLM summary).
- `assistant-dispatcher/src/main/java/com/zhongbo/mindos/assistant/dispatcher/DispatcherService.java`: intent routing and memory orchestration.
- `assistant-skill/src/main/java/com/zhongbo/mindos/assistant/skill/SkillEngine.java`: skill execution + procedural logging.
- `assistant-memory/src/main/java/com/zhongbo/mindos/assistant/memory/`: in-memory stores plus central memory repository selection (`CentralMemoryRepositoryConfig`) across file-backed default, in-memory fallback, and optional JDBC.
- `assistant-api/src/main/resources/application.properties`: app and LLM settings.
- `assistant-api/src/main/resources/application-solo.properties`: single-user experience profile overrides (cache/context/rollup/metrics token requirement).
- `scripts/unix/lib/mindos-env.sh`: shared shell env loader/validator for local and release startup; validates provider-routing map syntax, prints effective summaries, and flags placeholder drift.
- `scripts/unix/tools/check-secrets.sh`: canonical secrets preflight implementation (root `scripts/check-secrets.sh` remains a thin compatibility wrapper).
- `scripts/unix/cloud/`: cloud bootstrap/deploy/check/rollback helpers for single-host deployment flows.
- `dist/mindos-windows-server/`: packaged Windows server bundle + baseline `mindos-secrets.properties` consumed by `run-local.sh` / `run-release.sh` layering and Windows launch/smoke scripts.
- `assistant-sdk/src/main/java/com/zhongbo/mindos/assistant/sdk/AssistantSdkClient.java`: client-side HTTP SDK skeleton.
- `mindos-cli/src/main/java/com/zhongbo/mindos/assistant/cli/MindosCliApplication.java`: CLI entrypoint.
- `mindos-cli` also exposes `profile persona show` for inspecting the server-side learned persona profile.
- `assistant-common/src/main/java/com/zhongbo/mindos/assistant/common/dto/`: shared API/SDK DTOs (chat + memory sync contracts).

## Developer workflows
- Run all tests: `./mvnw -q test`
- Run API locally: `./mvnw -pl assistant-api -am spring-boot:run -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8`
- Run API locally in solo profile: `./mvnw -pl assistant-api -am spring-boot:run -Dspring-boot.run.profiles=solo -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8`
- Solo helper scripts: `./scripts/unix/local/run-mindos-solo.sh`, `./scripts/unix/local/solo-cli.sh`, `./scripts/unix/local/solo-smoke.sh`, `./scripts/unix/local/solo-stop.sh` (CLI semantics unchanged)
- Local env-layered startup: `./scripts/unix/local/run-local.sh` (loads `dist/mindos-windows-server/mindos-secrets.properties` first, then optional `config/secrets/mindos-secrets.local.properties`; supports `--dry-run` and `--strict`)
- Release env-layered startup/preflight: `./scripts/unix/local/run-release.sh` (loads dist secrets first, then optional `config/secrets/mindos-secrets.release.properties`; release mode is strict and supports `--dry-run`)
- Secrets/map preflight: `./scripts/check-secrets.sh --mode=local` or `./scripts/check-secrets.sh --mode=release`
- Build all modules: `./mvnw clean package`
- Export Windows bundle: `./scripts/unix/export/export-mindos-windows-dist.sh ./dist/mindos-windows-server` (set `SKIP_TESTS=0` to package with tests)
- Fast SDK/CLI validation: `./mvnw -q -pl assistant-sdk,mindos-cli -am test`
- Fast memory sync API validation: `./mvnw -q -pl assistant-api -am test -Dtest=MemorySyncControllerTest`
- Fast retrieve-preview security validation: `./mvnw -q -pl assistant-api -am test -Dtest=MemoryRetrievePreviewSecurityTest`
- Fast memory sync performance baseline validation: `./mvnw -q -pl assistant-memory -am test -Dtest=MemorySyncServiceTest#shouldMeetBasicSyncPerformanceBaseline -Dsurefire.failIfNoSpecifiedTests=false` (optional tuning: `-Dmindos.memory.sync.perf-baseline-ms=5000 -Dmindos.memory.sync.perf-retries=2`)
- Fast skill management API validation: `./mvnw -q -pl assistant-api -am test -Dtest=SkillControllerTest`
- Fast security audit API validation: `./mvnw -q -pl assistant-api -am test -Dtest=SecurityAuditApiTest`
- Fast IM webhook validation: `./mvnw -q -pl assistant-api -am test -Dtest=im.ImWebhookControllerTest`
- Fast solo profile smoke validation: `./mvnw -q -pl assistant-api -am test -Dtest=SoloProfileSmokeTest`
- Fast news skill validation: `./mvnw -q -pl assistant-skill -am test -Dtest=NewsSearchSkillTest`
- Fast DingTalk stream/message validation: `./mvnw -q -pl assistant-api -am test -Dtest=DingtalkStreamMessageDispatcherTest,DingtalkOpenApiConversationSenderTest`

 - Quick local semantic-analysis smoke & Ollama readiness (manual):
   1. Ensure Ollama (or compatible local server) is running and the quantized Gemma model is pulled and available.
   2. Verify HTTP endpoint reachable: `curl -sS http://localhost:11434/api/health | jq .` (or `curl -sS http://localhost:11434/api/chat -d '{"prompt":"hi"}'` depending on your runtime).
   3. Start the API with the solo profile after confirming `dist/mindos-windows-server/mindos-secrets.properties` maps point to the local provider: `./mvnw -pl assistant-api -am spring-boot:run -Dspring-boot.run.profiles=solo`

## Project conventions
- Keep package root under `com.zhongbo.mindos.assistant...`.
- Keep cross-module contracts in `assistant-common`; avoid module cycles.
- Prefer DTO contracts from `assistant-common` for API/SDK boundaries; map to memory model types inside `assistant-api`.
- Provider routing override for one chat request is passed via `AssistantProfileDto.llmProvider`; named cost/quality selection can also use `AssistantProfileDto.llmPreset`, both forwarded by `ChatController` into dispatcher/LLM context.
- Dispatcher prompt/reply budget and loop guard are configurable via `mindos.dispatcher.prompt.max-chars`, `mindos.dispatcher.memory-context.max-chars`, `mindos.dispatcher.llm-reply.max-chars`, `mindos.dispatcher.skill.guard.max-consecutive`, `mindos.dispatcher.skill.guard.recent-window-size`, `mindos.dispatcher.skill.guard.repeat-input-threshold`, and `mindos.dispatcher.skill.guard.cooldown-seconds`.
- Prompt-injection guard and risky operation policy are configurable via `mindos.dispatcher.prompt-injection.guard.*` and `mindos.security.risky-ops.*` / `mindos.security.skill.*`.
- One-time challenge approval uses `/api/security/challenge` and `mindos.security.risky-ops.challenge-*`; skill capability whitelist uses `mindos.security.skill.capability-*`; structured audit logs use `mindos.security.audit.*`.
- Metrics endpoint auth is configurable via `mindos.security.metrics.require-admin-token` (default enabled, validates `mindos.security.risky-ops.admin-token-*`).
- Challenge approval is strict (`operation + resource + actor + IP`, one-time consume); security audit supports traceId and recent-event query via `/api/security/audit` and filtered signed-cursor query via `/api/security/audit/query` (JWT-style cursor + expiry + key-version `kid`).
- MCP-loaded tools are namespaced as `mcp.<serverAlias>.<toolName>` to avoid collisions with built-in skills.
- Dispatcher auto-routing can execute registered skills via `Skill.supports(...)`, so MCP tool descriptions/names should stay specific enough to avoid accidental matches.
- Runtime LLM profile switching is centralized by `mindos.llm.profile` / `MINDOS_LLM_PROFILE` (e.g., `QWEN_STABLE`, `DOUBAO_STABLE`, `CUSTOM_LOCAL_FIRST`, `CN_DUAL`, `OPENROUTER_INTENT`, native single-vendor profiles); keep profile defaults, `mindos.llm.routing.preset-map`, and env template scripts aligned.
- Provider/routing env maps (`MINDOS_LLM_PROVIDER_ENDPOINTS`, `MINDOS_LLM_PROVIDER_KEYS`, `MINDOS_LLM_PROVIDER_MODELS`, `MINDOS_LLM_ROUTING_STAGE_MAP`, `MINDOS_LLM_ROUTING_PRESET_MAP`, MCP server/header maps) are comma-separated `key:value` entries without spaces; `scripts/unix/lib/mindos-env.sh` validates them during preflight/startup.
- Unix helper scripts are organized under `scripts/unix/*` (local/cloud/install/export/tools/lib); do not rely on removed root-level Unix wrappers other than the compatibility `scripts/check-secrets.sh`.
- New skills should implement `Skill` and be autodiscovered as Spring components.
- Preserve `contextLoads()` smoke test in `assistant-api` when adding integrations.
- Keep this repo single-user; central memory now defaults to file-backed storage under `data/memory-sync` when enabled, falls back to in-memory if file-repo initialization fails, and switches to JDBC automatically when a `DataSource` exists.
- If wiring persistence, follow `CentralMemoryRepositoryConfig`: preserve the file-backed no-`DataSource` path, keep in-memory fallback on initialization failure, and let JDBC activation stay conditional on `DataSource` presence.

## Agent checklist
- Before edits, confirm target module ownership and dependency direction.
- After Java/config changes, run at least `./mvnw -q test`.
- If you change env templates/startup scripts/provider-map parsing, run `./scripts/check-secrets.sh --mode=local` and one dry-run launcher (`./scripts/unix/local/run-local.sh --dry-run` or `./scripts/unix/local/run-release.sh --dry-run`).
- For SDK/CLI endpoint changes, run `./mvnw -q -pl assistant-sdk,mindos-cli -am test` first for fast feedback.
- If changing dispatch/skill behavior, test one `POST /chat` path (or backward-compatible `POST /api/chat`) manually or with unit tests.
- Update `README.md` and this file when workflows or module boundaries change.

### Minimal patch & verification checklist (preferred small, low-risk edits)
When implementing the local semantic + behavior-learning improvements, prefer the smallest change set that keeps the app bootable and tests passing. Suggested minimal edits:

- Files to consider (minimal, prioritized):
  1. `assistant-skill/src/main/java/com/zhongbo/mindos/assistant/skill/semantic/SemanticAnalysisService.java`
     - Ensure the analyzer outputs the structured fields used by dispatcher: `intent`, `payload` (or `params`), `summary`, `confidence`, and `suggestedSkill`.
     - Keep `llm` vs `local` provider resolution based on `mindos.dispatcher.semantic-analysis.*` properties; avoid embedding provider constants.
  2. `assistant-dispatcher/src/main/java/com/zhongbo/mindos/assistant/dispatcher/DispatcherService.java`
     - Wire in behavior-learning hooks already present (`behavior-learning.*` props). Validate `completeSemanticPayloadFromMemory()` and `maybeStoreBehaviorProfile()` paths to ensure missing params can be filled from `MemoryManager.getSkillUsageHistory()`.
     - Enable the parallel detected-skill routing flags for controlled A/B testing via config (`mindos.dispatcher.parallel-routing.*`).
  3. Tests to update / run:
     - `assistant-skill/src/test/java/com/zhongbo/mindos/assistant/skill/semantic/SemanticAnalysisServiceTest.java`
     - `assistant-dispatcher/src/test/java/com/zhongbo/mindos/assistant/dispatcher/DispatcherServiceTest.java`
     - Run these quickly with:
       - `./mvnw -q -pl assistant-skill -am test -Dtest=SemanticAnalysisServiceTest`
       - `./mvnw -q -pl assistant-dispatcher -am test -Dtest=DispatcherServiceTest`

- Quick verification steps after code edits:
  1. `./mvnw -q test` (run full test suite if CI time allows).
  2. Start API in `solo` profile and exercise `/api/chat` with a short input that triggers semantic analysis (e.g., "给 stu-1 做一个数学学习计划，六周，每周八小时") and confirm logs show `semantic-analysis` route and local model usage when expected.
  3. Verify behavior-learning writes to procedural memory: check `assistant-api/data/memory-sync` or in-memory fallbacks for `skill-usage` entries.

Notes:
- Prefer configuration-driven toggles over code changes — many features (semantic local escalation, parallel routing, behavior learning) are already feature-flagged via `mindos.*` properties. Edit properties first before changing code.
- Keep changes minimal in a single file where possible and iterate on tests. If a test fails, fix the implementation in the same module and re-run the relevant tests only.

---

### 技能开发与路由

#### 教学规划（teaching.plan）Skill
- 入口：`assistant-skill/src/main/java/com/zhongbo/mindos/assistant/skill/examples/TeachingPlanSkill.java`
- 支持输入字段（自动从自然语言抽取）：
  - `studentId`：学生标识
  - `topic`：主题/科目
  - `goal`：目标
  - `durationWeeks`：周期（支持中文数字到“万”位）
  - `weeklyHours`：每周投入小时数（支持中文数字到“万”位）
  - `gradeOrLevel`：年级/水平
  - `weakTopics`：薄弱点（数组）
  - `strongTopics`：优势项（数组）
  - `learningStyle`：学习风格（数组）
  - `constraints`：约束/不可用时段（数组）
  - `resourcePreference`：资源偏好（数组）
- 输出：结构化中文教学规划（阶段安排、每周建议、评估机制、风险提示、调整规则等）
- 路由规则见 `assistant-dispatcher/src/main/java/com/zhongbo/mindos/assistant/dispatcher/DispatcherService.java`，自动从自然语言抽取上述字段。

#### LLM 生成与降级
- teaching.plan 优先用 LLM 生成 JSON 结构，schema 校验失败自动降级为本地模板。
- LLM prompt 见 TeachingPlanSkill.java，输出仅允许 JSON。

#### 情商沟通（eq.coach）Skill
- 入口：`assistant-skill/src/main/java/com/zhongbo/mindos/assistant/skill/examples/EmotionalCoachSkill.java`
- 支持输入字段（CLI 自然语言会抽取）：
  - `query`：场景描述（必填语义）
  - `style`：`gentle|direct|workplace|intimate`
  - `mode`：`analysis|reply|both`
  - `priorityFocus`：`p1|p2|p3`
- 风险等级词表可由 `mindos.eq.coach.risk.high-terms`、`mindos.eq.coach.risk.medium-terms` 覆盖（默认词见 EmotionalCoachSkill.java）。

#### 新闻检索（news_search）Skill
- 入口：`assistant-skill/src/main/java/com/zhongbo/mindos/assistant/skill/examples/NewsSearchSkill.java`
- 功能：聚合 Google News RSS + 36kr，支持缓存、热点关键词提取、上下文总结；摘要默认走云侧 provider（`mindos.skill.news-search.summary-provider=qwen`），本地模型继续承担语义/调度与记忆优化。
- 参数：`source=google|36kr|all`、`sort=latest|relevance`、`limit=数字`，并支持自然语言条数（如“前五条”）。
- 关键配置：`mindos.skill.news-search.*`（Google RSS 模板、36kr feed、缓存 TTL、摘要模型/Token 等）。

---

### CLI 交互与自然语言指令

#### 交互显示默认值
- CLI 默认开启纯自然语言显示（隐藏 skill/channel/自动调度细节）。
- 排障时可通过 `--show-routing-details` 显示路由细节（`--pure-nl` 保留兼容）。
- 也可在会话中直接说“打开排障模式/关闭排障模式”切换显示细节，无需记忆参数。
- `/help` 默认给自然语言操作提示；技术命令与参数放在 `/help full`。
- memory 也支持自然语言入口：如“查看我的记忆风格”“按我的风格压缩这段记忆：...”。
- todo 策略支持会话内命令 `/todo policy show|set|reset`，并可通过 `mindos.todo.*` 系统属性设置默认阈值与文案。

#### 教学规划自然语言触发
- 支持直接在 CLI 对话窗口输入如：
  - “给学生 stu-1 做一个数学学习计划，目标是期末提分，六周，每周八小时，薄弱点函数、概率，学习风格练习优先”
- 自动识别为 `/teach plan --query ...`，并转为 teaching.plan 的 DSL JSON 请求。
- 相关代码：
  - NLU 规则：`mindos-cli/src/main/java/com/zhongbo/mindos/assistant/cli/CommandNluParser.java`
  - 交互入口：`mindos-cli/src/main/java/com/zhongbo/mindos/assistant/cli/InteractiveChatRunner.java`
- `/teach plan` 命令支持 `--query` 参数，未指定时窗口内引导输入。

#### 回归测试
- 相关测试用例：
  - `assistant-skill/src/test/java/com/zhongbo/mindos/assistant/skill/examples/TeachingPlanSkillTest.java`
  - `assistant-skill/src/test/java/com/zhongbo/mindos/assistant/skill/examples/EmotionalCoachSkillTest.java`
  - `assistant-api/src/test/java/com/zhongbo/mindos/assistant/api/ChatControllerTest.java`
  - `assistant-api/src/test/java/com/zhongbo/mindos/assistant/api/im/ImWebhookControllerTest.java`
  - `assistant-skill/src/test/java/com/zhongbo/mindos/assistant/skill/examples/NewsSearchSkillTest.java`
  - `mindos-cli/src/test/java/com/zhongbo/mindos/assistant/cli/CommandNluParserTest.java`
  - `mindos-cli/src/test/java/com/zhongbo/mindos/assistant/cli/MindosCliApplicationTest.java`

---

### 云端 API Skill（cloudapi）

#### 设计目标
允许通过 JSON 配置文件定义调用任意云端 REST API 的 Skill，并通过语义关键词实现自动路由匹配。

#### 核心文件
- `assistant-skill/src/main/java/com/zhongbo/mindos/assistant/skill/cloudapi/CloudApiSkillDefinition.java`：JSON 定义 record，描述 API endpoint、参数模板、关键词等
- `assistant-skill/src/main/java/com/zhongbo/mindos/assistant/skill/cloudapi/CloudApiSkill.java`：实现 `Skill` 接口，语义匹配 + HTTP 调用 + 结果提取
- `assistant-skill/src/main/java/com/zhongbo/mindos/assistant/skill/cloudapi/CloudApiSkillLoader.java`：Spring 组件，从配置目录加载 `.json` 定义并注册到 `SkillRegistry`

#### 配置方式（application.properties）
```properties
mindos.skills.cloud-api.config-dir=/path/to/cloud-skills
```

#### 定义文件示例（weather-query.json）
```json
{
  "name": "weather.query",
  "description": "Queries current weather for a given city",
  "keywords": ["天气", "weather", "温度", "forecast"],
  "url": "https://api.weatherstack.com/current",
  "method": "GET",
  "queryParams": {
    "access_key": "${apiKey}",
    "query": "${input.city}"
  },
  "apiKey": "your-api-key",
  "resultPath": "current",
  "resultTemplate": "天气：${weather_descriptions}, 温度：${temperature}°C, 湿度：${humidity}%"
}
```

#### 模板占位符规则
- `${input}` — 完整用户输入文本
- `${input.fieldName}` — SkillContext 中的 attribute 字段
- `${apiKey}` — 定义文件中的 `apiKey` 字段
- `${env.VAR_NAME}` — 系统环境变量

#### 语义匹配规则（supports()）
1. 用户输入以 skill name 开头时匹配
2. 输入包含任一 `keywords` 关键词时匹配（大小写不敏感）

#### API 热重载
- `POST /api/skills/reload-cloud` — 从配置目录重新加载所有云端 API skill 定义
- SDK：`client.reloadCloudApiSkills()`

#### 回归测试
- `assistant-skill/src/test/java/com/zhongbo/mindos/assistant/skill/cloudapi/CloudApiSkillTest.java`
- `assistant-skill/src/test/java/com/zhongbo/mindos/assistant/skill/cloudapi/CloudApiSkillLoaderTest.java`

---

### 其他
- Map.of 最多 10 对，超长参数请用 LinkedHashMap。
- 中文数字解析支持到“万”位。
- 交互全部窗口内完成，无需记忆命令格式。
- ONNX 嵌入 preset `bge-micro` 默认资源路径是 `assistant-memory/src/main/resources/models/bge-micro/model_quantized.onnx` 与 `assistant-memory/src/main/resources/models/bge-micro/tokenizer.json`；如改文件名需同步 `mindos.memory.embedding.onnx.model-path/tokenizer-path`。

---

如需进一步细化字段或有新业务需求，请补充说明。

---

**本次 AGENTS.md 主要新增/修改内容：**
- 校正 `assistant-memory` 默认持久化说明：补充 file-backed central repo、初始化失败回退到 in-memory、以及 JDBC 条件启用路径
- 增补 `/api/memory/{userId}/retrieve-preview` 流程、控制器映射与 `MemoryRetrievePreviewSecurityTest` 快速回归入口
- 增补 `run-local.sh` / `run-release.sh` 分层启动、`scripts/check-secrets.sh` 预检与 Windows bundle 导出命令
- Repository map 增补 `scripts/unix/lib/mindos-env.sh` 与 `dist/mindos-windows-server/` 关键路径说明
- 补充 `mindos.llm.routing.preset-map`、`mindos.dispatcher.intent-routing.*` 与 provider-map 语法校验约定
- 更新 agent checklist：涉及 env 模板/启动脚本/provider-map 解析时，先跑 secrets preflight 与 dry-run launcher

如需完整 AGENTS.md 文件或有其他模块变更，请告知！
