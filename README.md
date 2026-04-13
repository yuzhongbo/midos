# MindOS

[简体中文](README.zh-CN.md)

MindOS is a lightweight personal AI assistant backend built with **Java 17** and **Spring Boot 3.3.10**. The current core runtime is **Human-AI Co-Runtime**: AI can execute autonomously, but decisions stay explainable, interruptible, and human-correctable through a shared runtime instead of a one-way prompt/response flow.

## Core capabilities

- REST chat API (`/chat`, `/api/chat`) and CLI for daily assistant use
- Local-first dispatcher with multi-provider LLM routing and MCP/tool loading
- Memory system with persona / semantic / episodic / procedural layers
- Explicit goal execution through the autonomous runtime
- **Human-AI Co-Runtime** on top of the AGI runtime kernel:
  - shared decision gating
  - approval / wait states
  - human intervention and rollback
  - preference learning
  - trust-based autonomy adjustment

## Architecture at a glance

```text
Client (REST / CLI / IM / embedded goal call)
    -> DispatcherFacade / AgentLoop
    -> Human-AI Co-Runtime
       -> SharedDecisionEngine
       -> AGIRuntimeKernel
          -> RuntimeScheduler
          -> Cognitive plugins
             (planning / prediction / memory / reasoning / tool-use)
          -> ExecutionEngine
          -> AGIMemory
       -> InterventionManager
       -> HumanPreferenceModel
       -> TrustModel
```

There are two main execution paths:

1. **Public chat path**: `/chat` or CLI -> dispatcher -> skill or LLM response
2. **Explicit goal path**: `AgentLoop.runGoal(...)` / `AutonomousLoopEngine.run(...)` -> Human-AI Co-Runtime -> AGI kernel -> iterative execution

The public chat API is stable and user-facing. The explicit goal path is the place where advanced co-runtime attributes such as approvals, feedback queues, trust shaping, and runtime migration are currently wired.

## Modules

| Module | Responsibility |
| --- | --- |
| `assistant-api` | Spring Boot entrypoint, REST controllers, IM/webhook adapters |
| `assistant-dispatcher` | Dispatcher, memory-aware routing, autonomous runtime, Human-AI Co-Runtime |
| `assistant-memory` | Central memory, preference profiles, retrieval, sync |
| `assistant-skill` | Skill interfaces, registry, DSL, MCP/cloud API adapters |
| `assistant-llm` | LLM provider abstraction, multi-provider HTTP clients |
| `assistant-common` | Shared DTOs and contracts |
| `assistant-sdk` | Java SDK for calling the server |
| `mindos-cli` | Picocli interactive terminal client |

Dependency flow:

```text
assistant-api -> assistant-dispatcher -> (assistant-skill, assistant-memory, assistant-llm) -> assistant-common
mindos-cli -> assistant-sdk -> assistant-common
```

## Quick start

### Prerequisites

- JDK 17
- `./mvnw`
- Optional: Ollama for local-first semantic analysis
- Optional: provider keys (`OpenRouter`, `Qwen`, `OpenAI`, `Gemini`, etc.)

### Fast local setup

```bash
# 1) Regression check
./mvnw -q test

# 2) Create local override file
cp mindos-secrets.local.properties.example mindos-secrets.local.properties

# 3) Validate effective local config
chmod +x ./scripts/unix/local/run-local.sh ./scripts/unix/local/run-release.sh ./scripts/check-secrets.sh
./scripts/check-secrets.sh --mode=local
./scripts/unix/local/run-local.sh --dry-run

# 4) Start the API
./scripts/unix/local/run-local.sh
```

Alternative direct startup:

```bash
./mvnw -pl assistant-api -am spring-boot:run \
  -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8
```

### Solo profile

The `solo` profile is recommended for single-user daily use:

```bash
./mvnw -pl assistant-api -am spring-boot:run \
  -Dspring-boot.run.profiles=solo \
  -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8
```

Helpful scripts:

```bash
./scripts/unix/local/run-mindos-solo.sh
./scripts/unix/local/solo-cli.sh
./scripts/unix/local/solo-smoke.sh
./scripts/unix/local/solo-stop.sh
```

## How to use MindOS

### 1. Public chat API

`POST /chat` and `POST /api/chat` accept:

```json
{
  "userId": "local-user",
  "message": "help me summarize today's work",
  "profile": {
    "assistantName": "MindOS",
    "role": "coding-partner",
    "style": "concise",
    "language": "en-US",
    "timezone": "Asia/Shanghai",
    "llmProvider": "qwen",
    "llmPreset": "quality"
  }
}
```

Public `profile` currently maps to:

- `assistantName`
- `role`
- `style`
- `language`
- `timezone`
- `llmProvider`
- `llmPreset`

Example:

```bash
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{
    "userId":"local-user",
    "message":"what skills do you have?",
    "profile":{
      "style":"concise",
      "language":"en-US",
      "llmProvider":"qwen",
      "llmPreset":"balanced"
    }
  }'
```

History:

```bash
curl http://localhost:8080/api/chat/local-user/history
```

### 2. CLI

```bash
./mvnw -q -pl mindos-cli -am package
./mvnw -q -pl mindos-cli -am exec:java \
  -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication
```

Common commands:

- natural language: `show my skills`
- natural language: `pull my recent memory`
- slash: `/profile show`
- slash: `/memory pull --since 0 --limit 50`
- slash: `/skills`

Parameterized startup:

```bash
./mvnw -q -pl mindos-cli -am exec:java \
  -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication \
  -Dexec.args="--server http://localhost:8080 --user local-user --theme cyber"
```

### 3. Explicit goal execution / Human-AI Co-Runtime

Advanced co-runtime features are currently exposed through the embedded goal runtime, not through the public REST DTO.

Example:

```java
@Autowired
private AgentLoop agentLoop;

AutonomousGoalRunResult result = agentLoop.runGoal(
    "local-user",
    "fix the build, prepare release notes, and ask for approval before risky changes",
    Map.of(
        "executionPolicy", "autonomous",
        "runtimeTargetNode", "node:local",
        "human.preference.autonomy", 0.70,
        "human.preference.riskTolerance", 0.35,
        "human.preference.costSensitivity", 0.55,
        "human.preference.style", "concise",
        "human.approval.queue", List.of(
            Map.of("status", "approved", "reason", "safe to proceed")
        ),
        "human.feedback.queue", List.of(
            Map.of(
                "approved", false,
                "rollback", true,
                "notes", "use the safer plan",
                "corrections", Map.of("coruntime.allowedAgentIds", List.of("conservative-planner"))
            )
        )
    )
);
```

Returned `AutonomousGoalRunResult` now contains:

- `runtimeState`
- `runtimeHistory`
- `sharedDecisions`
- `interventionEvents`
- `humanPreference`
- `trustScore`
- `latestExplanation()`

## Configuration guide

### Configuration layers

Recommended order of configuration sources:

1. `assistant-api/src/main/resources/application.properties` – repository defaults
2. `application-solo.properties` or active Spring profiles – profile-specific defaults
3. `dist/mindos-windows-server/mindos-secrets.properties` – packaged distribution defaults
4. `mindos-secrets.local.properties` – local machine overrides
5. `mindos-secrets.release.properties` – release overrides
6. environment variables – final override layer

`./scripts/unix/local/run-local.sh` loads:

1. `dist/mindos-windows-server/mindos-secrets.properties`
2. `mindos-secrets.local.properties` (if present)

`./scripts/unix/local/run-release.sh` loads the dist file plus `mindos-secrets.release.properties` and fails fast on placeholders.

### Spring property vs environment variable naming

Spring Boot relaxed binding is supported, for example:

| Spring property | Environment variable |
| --- | --- |
| `mindos.llm.provider-endpoints` | `MINDOS_LLM_PROVIDER_ENDPOINTS` |
| `mindos.dispatcher.semantic-analysis.force-local` | `MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_FORCE_LOCAL` |
| `mindos.coruntime.approval-risk-threshold` | `MINDOS_CORUNTIME_APPROVAL_RISK_THRESHOLD` |

### Core configuration groups

| Concern | Spring property prefix | Typical env prefix | What it controls |
| --- | --- | --- | --- |
| LLM transport and routing | `mindos.llm.*` | `MINDOS_LLM_*` | HTTP enablement, provider maps, routing, retry, cache |
| Dispatcher behavior | `mindos.dispatcher.*` | `MINDOS_DISPATCHER_*` | semantic analysis, skill routing, local escalation, prompt shaping |
| Memory | `mindos.memory.*` | `MINDOS_MEMORY_*` | file repo, state persistence, embedding, filtering |
| Human-AI Co-Runtime | `mindos.coruntime.*` | `MINDOS_CORUNTIME_*` | approval/autonomy thresholds |
| Skills and MCP | `mindos.skills.*`, `mindos.skill.*` | `MINDOS_SKILLS_*`, `MINDOS_SKILL_*` | MCP servers, search sources, skill config |
| IM gateways | `mindos.im.*` | `MINDOS_IM_*` | DingTalk / Feishu / WeChat integration |
| Security | `mindos.security.*` | `MINDOS_SECURITY_*` | admin token and risky-op protection |

### LLM and local-first routing

Important keys:

| Key | Default | Purpose |
| --- | --- | --- |
| `mindos.llm.http.enabled` | `false` | Real HTTP calls; when `false`, stub mode is used |
| `mindos.llm.provider` | `stub` | Default provider alias |
| `mindos.llm.provider-endpoints` | empty | `provider:url` map |
| `mindos.llm.provider-models` | empty | `provider:model` map |
| `mindos.llm.provider-keys` | empty | `provider:key` map |
| `mindos.llm.routing.mode` | `fixed` | `fixed` or `auto` |
| `mindos.llm.routing.stage-map` | `llm-dsl:openai,llm-fallback:openai` | stage -> provider |
| `mindos.llm.routing.preset-map` | `cost:openai,balanced:openai,quality:openai` | preset -> provider |
| `mindos.dispatcher.semantic-analysis.enabled` | `true` | semantic analysis gate |
| `mindos.dispatcher.semantic-analysis.llm-enabled` | `false` | allow extra LLM semantic analysis |
| `mindos.dispatcher.semantic-analysis.force-local` | `true` | prefer local model for semantic stage |
| `mindos.dispatcher.semantic-analysis.llm-provider` | `local` | provider used for semantic analysis |
| `mindos.dispatcher.semantic-analysis.clarify-min-confidence` | `0.70` | low-confidence clarify gate |

### Human-AI Co-Runtime thresholds

These are the global knobs for shared control:

| Spring property | Default | Meaning |
| --- | --- | --- |
| `mindos.coruntime.approval-risk-threshold` | `0.68` | above this predicted risk, require human review |
| `mindos.coruntime.high-cost-threshold` | `0.75` | above this predicted cost, require review |
| `mindos.coruntime.approval-confidence-floor` | `0.55` | below this confidence, require review |
| `mindos.coruntime.autonomy-confidence-threshold` | `0.62` | minimum confidence for autonomous execution |
| `mindos.coruntime.min-trust-to-autonomy` | `0.45` | minimum learned trust score for autonomy |

These keys are read directly by `ControlProtocol`. They are not yet pre-populated in the env templates, but standard Spring env names work:

```properties
MINDOS_CORUNTIME_APPROVAL_RISK_THRESHOLD=0.70
MINDOS_CORUNTIME_HIGH_COST_THRESHOLD=0.80
MINDOS_CORUNTIME_AUTONOMY_CONFIDENCE_THRESHOLD=0.65
MINDOS_CORUNTIME_MIN_TRUST_TO_AUTONOMY=0.50
```

### Per-goal runtime attributes (embedded goal execution)

These are **runtime attributes**, not global Spring properties. They are passed in the `profileContext` map of `AgentLoop.runGoal(...)` / `AutonomousLoopEngine.run(...)`.

| Key | Type | Meaning |
| --- | --- | --- |
| `executionPolicy` | string | `realtime`, `batch`, `speculative`, `long-running`, `autonomous` |
| `runtimeTargetNode` | string | migrate initial execution to a specific node label/id |
| `human.preference.autonomy` | number `0..1` | user preference for autonomy |
| `human.preference.riskTolerance` | number `0..1` | tolerated risk |
| `human.preference.costSensitivity` | number `0..1` | cost sensitivity |
| `human.preference.style` | string | preferred decision / response style |
| `human.approval.default` | string | default approval mode, e.g. `approved`, `rejected` |
| `human.approval.queue` | list | queued approval decisions |
| `human.feedback.queue` | list | queued post-execution feedback |
| `coruntime.allowedAgentIds` | list/string | restrict planner agents for replanning |
| `coruntime.forceHumanReview` | boolean | always stop for review |
| `coruntime.forceHumanOverride` | boolean | force override path |
| `coruntime.overrideGraph` | `TaskGraph` object | internal Java-only direct plan replacement |

`coruntime.overrideGraph` is intended for embedded/in-process use only. It is **not** serializable through the current public `/chat` request DTO.

### Search and MCP configuration

For most cases:

- use `MINDOS_SKILLS_SEARCH_SOURCES` for search-style sources
- use `MINDOS_SKILLS_MCP_SERVERS` for generic MCP tools
- use `MINDOS_SKILLS_MCP_SERVER_HEADERS` for per-alias auth headers

Important keys:

| Key | Purpose |
| --- | --- |
| `mindos.skills.search-sources` / `MINDOS_SKILLS_SEARCH_SOURCES` | unified search source map |
| `mindos.skills.mcp-servers` / `MINDOS_SKILLS_MCP_SERVERS` | `alias:url` map for MCP servers |
| `mindos.skills.mcp-server-headers` / `MINDOS_SKILLS_MCP_SERVER_HEADERS` | `alias:Header=value;Header2=value2` |
| `mindos.skills.custom-dir` | custom JSON skills directory |
| `mindos.skills.external-jars` | external skill JAR URLs |

### Minimal local config example

`mindos-secrets.local.properties`:

```properties
MINDOS_LLM_PROFILE=CUSTOM_LOCAL_FIRST
MINDOS_LLM_MODE=LOCAL_FIRST
MINDOS_LLM_PROVIDER=qwen
MINDOS_LLM_PROVIDER_ENDPOINTS=local:http://localhost:11434/api/chat,qwen:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
MINDOS_LLM_PROVIDER_MODELS=local:gemma3:1b-it-q4_K_M,qwen:qwen3.6-plus
MINDOS_QWEN_KEY=REPLACE_WITH_QWEN_KEY
MINDOS_LLM_PROVIDER_KEYS=qwen:REPLACE_WITH_QWEN_KEY

MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_LLM_ENABLED=true
MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_FORCE_LOCAL=true
MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_LLM_PROVIDER=local
MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_CLARIFY_MIN_CONFIDENCE=0.70

MINDOS_CORUNTIME_APPROVAL_RISK_THRESHOLD=0.68
MINDOS_CORUNTIME_MIN_TRUST_TO_AUTONOMY=0.45

MINDOS_SKILLS_SEARCH_SOURCES=
```

Quick local Ollama check:

```bash
curl http://localhost:11434/api/chat \
  -d '{"model":"gemma3:1b-it-q4_K_M","messages":[{"role":"user","content":"Hello!"}]}'
```

If this fails, fix Ollama first; otherwise MindOS may appear routed to `local` while semantic analysis still cannot complete.

## Validation and deployment helpers

Recommended checks:

```bash
./scripts/check-secrets.sh --mode=local
./scripts/check-secrets.sh --mode=release
./scripts/unix/local/run-local.sh --dry-run
./scripts/unix/local/run-release.sh --dry-run
./mvnw -q test
```

Cloud helpers:

```bash
chmod +x ./scripts/unix/cloud/*.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/init-authorized-keys.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/cloud-init.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/deploy-cloud.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/cloud-check.sh
```

## Notes

- Real provider calls stay disabled until `mindos.llm.http.enabled=true` and valid provider maps/keys are set.
- File-backed memory and lightweight state persistence are enabled by default for restart continuity when no JDBC `DataSource` is present.
- `assistant-api/data/*` can change during tests because memory-state and H2 files are written there.
- The public REST DTO is intentionally conservative. Advanced Human-AI Co-Runtime controls are currently documented for embedded/internal goal execution rather than external `/chat` clients.
