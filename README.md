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
  - provider endpoint map: `mindos.llm.provider-endpoints=openai:https://api.openai.com/v1/chat/completions,local:http://localhost:11434/v1/chat/completions`
  - provider key map: `mindos.llm.provider-keys=openai:sk-xxx,local:dummy-key`
  - per-request override: send `profile.llmProvider` (CLI: `profile set --llm-provider openai`)
- Semantic memory is stored when input starts with `remember `.
- Memory sync API supports incremental pull via cursor (`since`) for multi-terminal synchronization.
- Memory compression planning supports gradual stages (`RAW -> CONDENSED -> BRIEF -> STYLED`) with per-user style profile via `POST /api/memory/{userId}/style`, `GET /api/memory/{userId}/style`, and `POST /api/memory/{userId}/compress-plan`.
- IM webhook integration (disabled by default) supports Feishu/DingTalk/WeChat text chat via `/api/im/feishu/events`, `/api/im/dingtalk/events`, `/api/im/wechat/events`; all platforms can enable signature verification independently in `application.properties`.
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

