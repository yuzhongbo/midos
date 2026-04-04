# MindOS

[简体中文](README.zh-CN.md)

MindOS is a lightweight, single-user personal AI assistant backend built with Java 17 and Spring Boot 3.2.x. It stays intentionally small: keep `assistant-api` bootable and the reactor test suite green, prefer in-memory defaults, and layer persistence only when needed.

## Modules
- `assistant-api`: Spring Boot REST API entrypoint (`/chat`, `/api/chat`)
- `assistant-dispatcher`: intent routing, skill execution, and LLM fallback
- `assistant-memory`: episodic/semantic/procedural memory with optional file/JDBC persistence
- `assistant-skill`: skill interfaces, registry, DSL, built-in skills, MCP/tool adapters
- `assistant-llm`: API-key-based LLM client (stubbed when no key is present)
- `assistant-common`: shared DTOs/contracts (`SkillDsl`, `SkillContext`, `SkillResult`, `LlmClient`)
- `assistant-sdk`: Java client SDK for server calls
- `mindos-cli`: Picocli CLI on top of `assistant-sdk`; defaults to interactive chat with slash commands

Dependency flow: `assistant-api -> assistant-dispatcher -> (assistant-skill, assistant-memory, assistant-llm) -> assistant-common`; `mindos-cli -> assistant-sdk -> assistant-common`.

## Quick Start
```bash
# Fast correctness check
./mvnw -q test

# Run API locally (UTF-8 ensured)
./mvnw -pl assistant-api -am spring-boot:run -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8
```

## Solo Profile (single-user friendly)
The `solo` Spring profile optimizes for day-to-day personal use: warmer replies, slightly longer context, short-TTL LLM cache, and relaxed metrics auth.

```bash
./mvnw -pl assistant-api -am spring-boot:run \
  -Dspring-boot.run.profiles=solo \
  -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8
```

Helpers:
```bash
chmod +x ./scripts/unix/local/*.sh ./scripts/check-secrets.sh
./scripts/unix/local/run-mindos-solo.sh          # one-click local server
./scripts/unix/local/run-local.sh                # load dist secrets + optional local overrides, then start solo
./scripts/unix/local/run-local.sh --dry-run      # preflight only: print effective summary and exit
./scripts/unix/local/run-local.sh --strict       # fail if placeholders are still active
./scripts/unix/local/run-release.sh              # release startup with strict placeholder checks
./scripts/unix/local/run-release.sh --dry-run    # release preflight only (strict)
./scripts/check-secrets.sh --mode=local
./scripts/check-secrets.sh --mode=release
./scripts/unix/local/solo-cli.sh                 # CLI with defaults
./scripts/unix/local/solo-smoke.sh               # lightweight /chat and /api/metrics/llm check
./scripts/unix/local/solo-stop.sh                # stop by port or process pattern
```

Local key management for debugging (recommended):
```bash
cp mindos-secrets.local.properties.example mindos-secrets.local.properties
chmod +x ./scripts/unix/local/run-local.sh
./scripts/unix/local/run-local.sh
```
- `scripts/unix/local/run-local.sh` loads `dist/mindos-windows-server/mindos-secrets.properties` first, then optional `mindos-secrets.local.properties` overrides.
- `scripts/unix/local/run-release.sh` loads `dist/.../mindos-secrets.properties` plus optional `mindos-secrets.release.properties`, and fails fast on placeholder secrets.
- Keep real keys only in `mindos-secrets.local.properties` / `mindos-secrets.release.properties` (both ignored by git).

### Secrets file layout and multi-provider routing

`dist/mindos-windows-server/mindos-secrets.properties` uses a three-part layout:
- `1) 建议默认`: safe defaults that usually stay enabled in packaged dist, e.g. `MINDOS_LLM_PROFILE=QWEN_STABLE` and a single-provider `qwen` default.
- `2) 可选填`: values that are intentionally left blank in dist, such as `MINDOS_SKILLS_MCP_SERVERS`, `MINDOS_SKILLS_MCP_SERVER_HEADERS`, and DingTalk stream/outbound credentials.
- `3) 必须填`: release placeholders that strict prechecks will reject until replaced, currently centered on `MINDOS_QWEN_KEY` and `MINDOS_LLM_PROVIDER_KEYS`.

For multi-provider setups, these variables are comma-separated provider maps:
- `MINDOS_LLM_PROVIDER_ENDPOINTS`: `provider:baseUrl,provider2:baseUrl2`
- `MINDOS_LLM_PROVIDER_KEYS`: `provider:key,provider2:key2`
- `MINDOS_LLM_PROVIDER_MODELS`: `provider:modelId,provider2:modelId2`

Examples:
- Local-first token-saving setup: `local:http://localhost:11434/api/chat,qwen:https://dashscope.aliyuncs.com/...`
- Single-provider release setup: `MINDOS_LLM_PROFILE=QWEN_STABLE`, `MINDOS_LLM_PROVIDER=qwen`, `MINDOS_LLM_PROVIDER_KEYS=qwen:...`
- Stage-based cloud routing: `MINDOS_LLM_ROUTING_STAGE_MAP=llm-dsl:openrouter,llm-fallback:qwen`

Recommended routing patterns from `mindos-server.env.template.sh`:
- `CUSTOM_LOCAL_FIRST`: local Ollama endpoint handles semantic analysis / low-cost requests first, cloud provider stays available for stronger fallback.
- Multi-cloud stage routing: use `MINDOS_LLM_ROUTING_STAGE_MAP` and `MINDOS_LLM_ROUTING_PRESET_MAP` to pin providers by dispatcher stage or preset.
- `QWEN_STABLE`: simplest production mode when you want one provider, one key map, and easy auditability.

Validation rules:
- Do not put spaces inside provider maps; entries are comma-separated and each entry is split at the first `:`.
- `scripts/unix/lib/mindos-env.sh` validates map syntax via `mindos_validate_kv_map_format` during startup/export flows.
- Keep real secrets in `mindos-secrets.local.properties` or `mindos-secrets.release.properties`; keep dist templates placeholder-only to reduce config drift and accidental secret leakage.

### Minimal local Ollama + Qwen example

If you want low-cost local semantic analysis first, then Qwen for stronger cloud replies when needed, this is the smallest practical env-style setup:

```properties
MINDOS_LLM_PROFILE=CUSTOM_LOCAL_FIRST
MINDOS_LLM_PROVIDER=qwen
MINDOS_LLM_PROVIDER_ENDPOINTS=local:http://localhost:11434/api/chat,qwen:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
MINDOS_LLM_PROVIDER_MODELS=local:gemma4:e2b-it-q4_K_M,qwen:qwen3.5-plus
MINDOS_QWEN_KEY=REPLACE_WITH_QWEN_KEY
MINDOS_LLM_PROVIDER_KEYS=qwen:REPLACE_WITH_QWEN_KEY

MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_LLM_ENABLED=true
MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_FORCE_LOCAL=true
MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_LOCAL_ESCALATION_ENABLED=true
MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_LLM_PROVIDER=local
MINDOS_DISPATCHER_LLM_FALLBACK_PROVIDER=qwen
```

What this setup is for:
- `local:http://localhost:11434/api/chat` is used as the cheap local endpoint for semantic analysis and short, low-cost reasoning.
- `qwen` stays available as the cloud provider for stronger fallback/final reply quality.
- Keep the real Qwen secret in `mindos-secrets.local.properties` or `mindos-secrets.release.properties`, not in the dist template.

Quick local check before starting MindOS:

```bash
curl http://localhost:11434/api/chat \
  -d '{"model":"gemma4:e2b-it-q4_K_M","messages":[{"role":"user","content":"Hello!"}]}'
```

If this call does not return successfully, fix Ollama first; otherwise MindOS may log routing to `local` but still fail before real semantic-analysis output is produced.

### Pre-release Checklist (3 Commands)

```bash
./scripts/check-secrets.sh --mode=release
./scripts/unix/local/run-release.sh --dry-run
./mvnw -q test
```

Script layout (organized by OS first, then role):
- `scripts/unix/local/*`: local dev/runtime launchers (`run-local`, `run-release`, `run-mindos-solo`, `solo-*`)
- `scripts/unix/cloud/*`: cloud bootstrap/deploy/rollback helpers (`init-authorized-keys`, `cloud-init`, `deploy-cloud`, `cloud-check`, `rollback-cloud`)
- `scripts/unix/install/*`: Unix install/uninstall helpers (`install-mindos-*`, `uninstall-mindos-*`)
- `scripts/unix/export/*`: packaging/export helpers (`export-mindos-windows-dist`)
- `scripts/unix/tools/*`: preflight/check helpers (`check-secrets`)
- `scripts/unix/lib/*`: shared shell utilities (`mindos-env.sh`)
- `scripts/windows/*`: Windows launch/install/smoke helpers (`*.bat`)
- root Unix wrapper scripts are removed; use the `scripts/unix/*` paths directly.

Windows scripts are centralized in `scripts/windows/*`.

## CLI Quick Start
Three-minute path (natural language first):
```bash
./mvnw -q -pl mindos-cli -am test
./mvnw -q -pl mindos-cli -am package
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication
```

Try in-session phrases (no flags required):
- `我有哪些技能`
- `帮我拉取最近 30 条记忆`
- `给学生 stu-1 做数学学习计划，六周，每周八小时`
- `打开排障模式` / `关闭排障模式`

Need parameters? Examples:
```bash
./mvnw -q -pl mindos-cli -am exec:java \
  -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication \
  -Dexec.args="--server http://localhost:8080 --user local-user"

./mvnw -q -pl mindos-cli -am exec:java \
  -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication \
  -Dexec.args="profile set --llm-provider openai --style concise"
```

Common natural-language mappings:
- `查看我的记忆风格` -> `/memory style show`
- `按我的风格压缩这段记忆：明天先拆任务再推进联调` -> `/memory compress --source ...`
- `请做情感沟通指导，职场版，优先级 p1` -> `/eq coach --query ... --style workplace --priority-focus p1`

## Sample API Requests
```bash
# Chat
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"userId":"local-user","message":"echo hello"}'

# Memory sync
curl -X POST http://localhost:8080/api/memory/local-user/sync \
  -H 'Content-Type: application/json' \
  -d '{"episodic":[{"role":"user","content":"hello from terminal A"}]}'

# Skill listing
curl http://localhost:8080/api/skills
```

## Cloud Deploy (single host)
Passwordless flow (recommended):
```bash
chmod +x ./scripts/unix/cloud/*.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/init-authorized-keys.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/cloud-init.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/deploy-cloud.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/cloud-check.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/rollback-cloud.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/cloud-check.sh
```

## Validation Shortcuts
- Full suite: `./mvnw -q test`
- Fast SDK/CLI: `./mvnw -q -pl assistant-sdk,mindos-cli -am test`
- Targeted API: `./mvnw -q -pl assistant-api -am test -Dtest=MemorySyncControllerTest`

## Notes
- Real LLM calls stay disabled until you set provider endpoints/keys (`mindos.llm.http.enabled=true` + key map). Stub mode is the default.
- In-memory first: central memory falls back to file storage when enabled and no `DataSource` is present; preference profiles/long tasks/style profiles persist to `data/memory-state` by default.
- MCP/Cloud API/custom skills can be hot-loaded; see `assistant-skill` loaders and `/api/skills/*` endpoints.

For the full Chinese guide (including IM integration, routing, and configuration matrices), read [README.zh-CN.md](README.zh-CN.md).