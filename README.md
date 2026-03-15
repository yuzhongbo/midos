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
```bash
./mvnw -q -pl mindos-cli -am test
./mvnw -q -pl mindos-cli -am package
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="--help"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="--server http://localhost:8080 --user local-user"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="init --name BoAssistant --role coding-partner --style concise --language zh-CN --timezone Asia/Shanghai"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="profile show"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="profile set --style detailed --timezone UTC"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="profile set --llm-provider openai"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="profile reset"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="chat --user local-user --message 'echo hello' --server http://localhost:8080"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="chat --interactive --server http://localhost:8080"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="memory pull --user local-user --since 0 --limit 100 --server http://localhost:8080"
./mvnw -q -pl mindos-cli -am exec:java -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication -Dexec.args="memory push --user local-user --file /tmp/memory-sync.json --limit 100 --server http://localhost:8080"
```

交互模式内置命令：

```text
/help
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
/exit
```

`/memory push` 现在支持在一个聊天窗口内逐步录入记忆：可直接输入 `semantic / episodic / procedural`（也支持 `语义 / 对话 / 流程`），完成后会先显示预览，再提交给服务端；服务端会自动做文本规范化、语义记忆去重、embedding 压缩与更偏近期/相关性的检索整理。

交互模式下，现有 slash 命令也支持自然语言触发（例如：`我有哪些技能`、`帮我拉取记忆`、`请加载jar https://...`）。对高风险操作（如重置 profile、加载外部 JAR/MCP、切换 server）会在对话中二次确认。`/memory pull` 与 `/memory push` 会在后台异步执行，不阻塞继续聊天。

参数化自然语言示例：
- `帮我从 12 开始拉取最近 30 条记忆` -> `/memory pull --since 12 --limit 30`
- `从 12 开始拉取 30 条记忆` -> `/memory pull --since 12 --limit 30`
- `查看最近 5 条历史` / `给我看几条历史` -> `/history --limit 5` / `/history --limit 10`
- `查看最近十条历史` / `帮我保存二十条记忆` -> `/history --limit 10` / `/memory push --limit 20`
- `从 2 开始拉三十条` -> `/memory pull --since 2 --limit 30`
- `查看最近一千二百条历史` / `从 3 开始拉一千零二十条记忆` -> `/history --limit 1200` / `/memory pull --since 3 --limit 1020`
- `查看最近一万二千条历史` / `从 3 开始拉一万零二十条记忆` -> `/history --limit 12000` / `/memory pull --since 3 --limit 10020`
- `把用户改为 dev-user` -> `/user dev-user`
- `把服务地址换成 http://localhost:18080` -> `/server http://localhost:18080`（需确认）
- `把服务端地址改成 localhost:19090` -> `/server http://localhost:19090`（自动补全协议，需确认）
- `把模型切换到 openai` / `取消模型覆盖` -> `/provider openai` / `/provider default`
- `请接入mcp https://docs.example.com/mcp，简称 docs-cn` -> `/skill load-mcp --alias docs-cn --url ...`（需确认）

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

curl -X POST http://localhost:8080/api/memory/local-user/sync \
  -H 'Content-Type: application/json' \
  -d '{
    "episodic": [{"role": "user", "content": "hello from terminal A"}],
    "semantic": [{"text": "order api design", "embedding": [1.0, 0.2]}],
    "procedural": [{"skillName": "code.generate", "input": "create order api", "success": true}]
  }'

curl "http://localhost:8080/api/memory/local-user/sync?since=0&limit=100"

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

