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
chmod +x ./run-mindos-solo.sh ./solo-cli.sh ./solo-smoke.sh ./solo-stop.sh
./run-mindos-solo.sh          # one-click local server
./solo-cli.sh                 # CLI with defaults
./solo-smoke.sh               # lightweight /chat and /api/metrics/llm check
./solo-stop.sh                # stop by port or process pattern
```

Windows equivalents live beside the scripts above (`run-mindos-solo.bat`, `solo-smoke.bat`, `install-mindos-server.bat`).

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
chmod +x ./init-authorized-keys.sh ./cloud-init.sh ./cloud-check.sh ./deploy-cloud.sh ./rollback-cloud.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./init-authorized-keys.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./cloud-init.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./deploy-cloud.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./cloud-check.sh
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
