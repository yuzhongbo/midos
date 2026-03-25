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
./mvnw -pl assistant-api -am spring-boot:run
```

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
- LLM calls are stubbed unless `mindos.llm.api-key` is set in `assistant-api/src/main/resources/application.properties`.
- Optional multi-provider routing:
  - default provider: `mindos.llm.provider=stub`
  - routing mode: `mindos.llm.routing.mode=fixed|auto` (default `fixed`)
  - auto stage mapping: `mindos.llm.routing.stage-map=llm-dsl:openai,llm-fallback:openai`
  - preset mapping: `mindos.llm.routing.preset-map=cost:openai,balanced:openai,quality:openai`
  - provider endpoint map: `mindos.llm.provider-endpoints=openai:https://api.openai.com/v1/chat/completions,local:http://localhost:11434/v1/chat/completions`
  - provider key map: `mindos.llm.provider-keys=openai:sk-xxx,local:dummy-key`
  - mainland model key map example: `mindos.llm.provider-keys=deepseek:sk-xxx,qwen:sk-yyy,kimi:sk-zzz,doubao:ark-aaa`
  - mainland aliases are supported (`dashscope/tongyi -> qwen`, `moonshot -> kimi`, `volcengine -> doubao`, `zhipu -> glm`, `baidu -> ernie`).
  - when endpoint map is empty, built-in mainland defaults are used for `deepseek/qwen/kimi/doubao/hunyuan/ernie/glm`.
  - per-request override: send `profile.llmProvider` (CLI: `profile set --llm-provider openai`; use `auto` to force automatic stage-based routing)
  - per-request/server-profile preset: send `profile.llmPreset` (CLI: `profile set --llm-preset quality`) to pick a named cost/quality preset before stage auto-routing.
- LLM call metrics (token estimate + multi-provider stats):
  - endpoint: `GET /api/metrics/llm?windowMinutes=60&provider=openai&includeRecent=true&recentLimit=20`
  - toggles: `mindos.llm.metrics.enabled` (default `true`), `mindos.llm.metrics.max-recent-calls` (default `500`)
  - optional auth: `mindos.security.metrics.require-admin-token` (default `true`, validates `mindos.security.risky-ops.admin-token-header` / `mindos.security.risky-ops.admin-token`)
  - summary includes success/fallback rate, average latency, estimated token totals, provider aggregates, optional recent calls, and `securityAudit` writer metrics (`queueDepth`, `enqueuedCount`, `writtenCount`, `callerRunsFallbackCount`, `flushTimeoutCount`, `flushErrorCount`).
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
- Semantic memory is stored when input starts with `remember `.
- Dispatcher habit-routing confidence controls (optional, app/JVM properties):
  - `mindos.dispatcher.habit-routing.enabled` (default `true`)
  - `mindos.dispatcher.habit-routing.min-total-count` (default `2`)
  - `mindos.dispatcher.habit-routing.min-success-rate` (default `0.6`)
  - `mindos.dispatcher.habit-routing.recent-window-size` (default `6`)
  - `mindos.dispatcher.habit-routing.recent-min-success-count` (default `2`)
  - `mindos.dispatcher.habit-routing.recent-success-max-age-hours` (default `72`)
  - continuation auto-routing now requires leading continuation cues (like `继续/按之前`) and stable recent success history.
- Dispatcher token/loop guards:
  - `mindos.dispatcher.prompt.max-chars` (default `2800`)
  - `mindos.dispatcher.memory-context.max-chars` (default `1800`)
  - `mindos.dispatcher.llm-reply.max-chars` (default `1200`)
  - `mindos.dispatcher.skill.guard.max-consecutive` (default `2`), blocks repeated same-skill loop routing and falls back to broader reasoning.
  - `mindos.dispatcher.skill.guard.recent-window-size` (default `6`), recent procedural entries scanned for loop fingerprints.
  - `mindos.dispatcher.skill.guard.repeat-input-threshold` (default `2`), repeated same-skill + same-input fingerprints allowed within cooldown before blocking.
  - `mindos.dispatcher.skill.guard.cooldown-seconds` (default `180`), cooldown window for repeated same-input loop detection.
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
  - search recency decay half-life hours: `mindos.memory.search.decay-half-life-hours` (default `72`)
  - explicit preferred-bucket search cross-bucket fallback cap: `mindos.memory.search.cross-bucket.max` (default `2`)
  - explicit preferred-bucket search cross-bucket fallback ratio: `mindos.memory.search.cross-bucket.ratio` (default `0.5`, range `0..1`)
  - when enabled, low-signal short semantic entries are skipped; retrieval prefers same inferred topic bucket and keeps bounded cross-bucket fallback when preferred bucket is explicit.
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
      -Dspring-boot.run.jvmArguments="-Dmindos.memory.nlu.focus.review-terms=复盘,总结,retrospective -Dmindos.memory.nlu.style.action-terms=action,行动,行动派 -Dmindos.memory.nlu.tone.warm-terms=warm,温和,gentle -Dmindos.memory.nlu.format.bullet-terms=bullet,列表,markdown list"
    ```
  - prod example:
    ```bash
    java \
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
- Skill management API: `GET /api/skills` lists all registered skills.
- Chat-style skill discovery is supported for everyday phrasing such as `你有哪些技能？`, `你能做什么？`, and `你还可以学习哪些技能？`.

## Fast validation sequence
```bash
./mvnw -q -pl assistant-sdk,mindos-cli -am test
./mvnw -q -pl assistant-api -am test -Dtest=MemorySyncControllerTest
./mvnw -q test
```

