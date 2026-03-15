# AGENTS.md

## Purpose
This repository is a lightweight, single-user personal AI assistant backend. Optimize for small, safe changes that keep `assistant-api` bootable and the reactor test suite passing.

## Existing AI instruction sources
- Glob scan for `**/{.github/copilot-instructions.md,AGENT.md,AGENTS.md,CLAUDE.md,.cursorrules,.windsurfrules,.clinerules,.cursor/rules/**,.windsurf/rules/**,.clinerules/**,README.md}` found only this file and `README.md`.
- Treat this file as primary coding-agent guidance.

## Architecture snapshot
- Root `pom.xml` is an aggregator (`packaging=pom`) using Java `17` and Spring Boot `3.2.5`.
- Modules and boundaries:
  - `assistant-api`: REST entrypoint and Spring Boot app
  - `assistant-dispatcher`: routes user input to skills/LLM
  - `assistant-memory`: episodic, semantic, procedural memory services (in-memory default; JDBC central repository auto-enabled when a `DataSource` exists)
  - `assistant-skill`: skill interface, registry, DSL execution, example skills; `loader/` sub-package for custom JSON skills and external JAR plugins; `mcp/` sub-package for MCP tool adapters over HTTP JSON-RPC
  - `assistant-llm`: API-key-based LLM client adapter (supports global/per-user keys plus provider-specific keys/endpoints; stubbed response when key missing)
  - `assistant-common`: shared contracts (`SkillDsl`, `SkillContext`, `SkillResult`, `LlmClient`)
  - `assistant-sdk`: Java SDK for client-side server calls
  - `mindos-cli`: Picocli command-line client using `assistant-sdk`; default entrypoint opens an interactive chat session and slash commands handle common session/profile/memory actions in one window
- Runtime flow: `/chat` (backward compatible with `/api/chat`) -> dispatcher -> (DSL/skill engine or LLM fallback) -> memory updates.
- Multi-terminal memory flow: `/api/memory/{userId}/sync` with cursor-based pull (`GET`) and idempotent push (`POST eventId`).

## Repository map
- `assistant-api/src/main/java/com/zhongbo/mindos/assistant/api/ChatController.java`: chat HTTP interface.
- `assistant-api/src/main/java/com/zhongbo/mindos/assistant/api/SkillController.java`: lists/reloads custom skills and loads external JAR skills.
- `assistant-skill/src/main/java/com/zhongbo/mindos/assistant/skill/mcp/McpSkillLoader.java`: maps configured MCP servers to namespaced skills (`mcp.<alias>.<tool>`).
- `assistant-dispatcher/src/main/java/com/zhongbo/mindos/assistant/dispatcher/DispatcherService.java`: intent routing and memory orchestration.
- `assistant-skill/src/main/java/com/zhongbo/mindos/assistant/skill/SkillEngine.java`: skill execution + procedural logging.
- `assistant-memory/src/main/java/com/zhongbo/mindos/assistant/memory/`: in-memory stores plus optional JDBC-backed central memory repository selection (`CentralMemoryRepositoryConfig`).
- `assistant-api/src/main/resources/application.properties`: app and LLM settings.
- `assistant-sdk/src/main/java/com/zhongbo/mindos/assistant/sdk/AssistantSdkClient.java`: client-side HTTP SDK skeleton.
- `mindos-cli/src/main/java/com/zhongbo/mindos/assistant/cli/MindosCliApplication.java`: CLI entrypoint.
- `assistant-common/src/main/java/com/zhongbo/mindos/assistant/common/dto/`: shared API/SDK DTOs (chat + memory sync contracts).

## Developer workflows
- Run all tests: `./mvnw -q test`
- Run API locally: `./mvnw -pl assistant-api -am spring-boot:run`
- Build all modules: `./mvnw clean package`
- Fast SDK/CLI validation: `./mvnw -q -pl assistant-sdk,mindos-cli -am test`
- Fast memory sync API validation: `./mvnw -q -pl assistant-api -am test -Dtest=MemorySyncControllerTest`
- Fast skill management API validation: `./mvnw -q -pl assistant-api -am test -Dtest=SkillControllerTest`

## Project conventions
- Keep package root under `com.zhongbo.mindos.assistant...`.
- Keep cross-module contracts in `assistant-common`; avoid module cycles.
- Prefer DTO contracts from `assistant-common` for API/SDK boundaries; map to memory model types inside `assistant-api`.
- Provider routing override for one chat request is passed via `AssistantProfileDto.llmProvider` and forwarded by `ChatController` into dispatcher/LLM context.
- MCP-loaded tools are namespaced as `mcp.<serverAlias>.<toolName>` to avoid collisions with built-in skills.
- Dispatcher auto-routing can execute registered skills via `Skill.supports(...)`, so MCP tool descriptions/names should stay specific enough to avoid accidental matches.
- New skills should implement `Skill` and be autodiscovered as Spring components.
- Preserve `contextLoads()` smoke test in `assistant-api` when adding integrations.
- Keep this repo single-user and in-memory by default; persistence can be layered later.
- If wiring persistence, follow `CentralMemoryRepositoryConfig`: keep in-memory fallback and let JDBC activation be conditional on `DataSource` presence.

## Agent checklist
- Before edits, confirm target module ownership and dependency direction.
- After Java/config changes, run at least `./mvnw -q test`.
- For SDK/CLI endpoint changes, run `./mvnw -q -pl assistant-sdk,mindos-cli -am test` first for fast feedback.
- If changing dispatch/skill behavior, test one `POST /chat` path (or backward-compatible `POST /api/chat`) manually or with unit tests.
- Update `README.md` and this file when workflows or module boundaries change.
