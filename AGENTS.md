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

---

### CLI 交互与自然语言指令

#### 交互显示默认值
- CLI 默认开启纯自然语言显示（隐藏 skill/channel/自动调度细节）。
- 排障时可通过 `--show-routing-details` 显示路由细节（`--pure-nl` 保留兼容）。
- 也可在会话中直接说“打开排障模式/关闭排障模式”切换显示细节，无需记忆参数。
- `/help` 默认给自然语言操作提示；技术命令与参数放在 `/help full`。

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
  - `assistant-api/src/test/java/com/zhongbo/mindos/assistant/api/ChatControllerTest.java`
  - `mindos-cli/src/test/java/com/zhongbo/mindos/assistant/cli/CommandNluParserTest.java`
  - `mindos-cli/src/test/java/com/zhongbo/mindos/assistant/cli/MindosCliApplicationTest.java`

---

### 其他
- Map.of 最多 10 对，超长参数请用 LinkedHashMap。
- 中文数字解析支持到“万”位。
- 交互全部窗口内完成，无需记忆命令格式。

---

如需进一步细化字段或有新业务需求，请补充说明。

---

**本次 AGENTS.md 主要新增/修改内容：**
- teaching.plan skill 多维画像与 LLM/schema 路径说明
- Dispatcher/CLI 端自然语言教学规划路由与参数抽取说明
- CLI `/teach plan` 命令与自然语言触发说明
- 相关测试与兼容性注意事项

如需完整 AGENTS.md 文件或有其他模块变更，请告知！
