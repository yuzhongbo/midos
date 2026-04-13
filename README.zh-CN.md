# MindOS

English version: [README.md](README.md)

MindOS 是一个基于 **Java 17** 和 **Spring Boot 3.3.10** 的轻量级个人 AI 助手后端。当前核心运行形态已经升级为 **Human-AI Co-Runtime（人机共运行系统）**：AI 可以自主执行，但所有关键决策都保持可解释、可中断、可回滚，并且可以在运行时被人类纠正。

## 核心能力

- REST Chat API（`/chat`、`/api/chat`）和 CLI 日常使用入口
- local-first 调度器，支持多 provider LLM 路由与 MCP / Tool 加载
- Persona / Semantic / Episodic / Procedural 四层记忆
- 显式 Goal 执行与自治循环
- **Human-AI Co-Runtime**：
  - 共享决策门控
  - 人工审批 / 等待态
  - 人类干预、修改、回滚
  - 偏好学习
  - 基于 trust 的自动化强度调节

## 架构概览

```text
客户端（REST / CLI / IM / 内嵌 Goal 调用）
    -> DispatcherFacade / AgentLoop
    -> Human-AI Co-Runtime
       -> SharedDecisionEngine
       -> AGIRuntimeKernel
          -> RuntimeScheduler
          -> Cognitive plugins
             （planning / prediction / memory / reasoning / tool-use）
          -> ExecutionEngine
          -> AGIMemory
       -> InterventionManager
       -> HumanPreferenceModel
       -> TrustModel
```

当前有两条主要执行路径：

1. **公开聊天路径**：`/chat` 或 CLI -> dispatcher -> skill 或 LLM 回复
2. **显式目标执行路径**：`AgentLoop.runGoal(...)` / `AutonomousLoopEngine.run(...)` -> Human-AI Co-Runtime -> AGI Runtime Kernel -> 多轮执行

其中：

- **公开 chat API** 是面向外部客户端的稳定入口
- **显式 goal runtime** 是当前承载 co-runtime 高级能力（审批、反馈队列、信任调节、迁移执行等）的主入口

## 模块

| 模块 | 作用 |
| --- | --- |
| `assistant-api` | Spring Boot 入口、REST Controller、IM/Webhook 集成 |
| `assistant-dispatcher` | 调度器、记忆感知路由、自治运行时、Human-AI Co-Runtime |
| `assistant-memory` | 中央记忆、偏好画像、检索、同步 |
| `assistant-skill` | Skill 接口、注册表、DSL、MCP / Cloud API 适配 |
| `assistant-llm` | LLM provider 抽象与多 provider HTTP 客户端 |
| `assistant-common` | 共享 DTO 与契约 |
| `assistant-sdk` | Java SDK |
| `mindos-cli` | Picocli 命令行客户端 |

依赖关系：

```text
assistant-api -> assistant-dispatcher -> (assistant-skill, assistant-memory, assistant-llm) -> assistant-common
mindos-cli -> assistant-sdk -> assistant-common
```

## 快速开始

### 前置要求

- JDK 17
- `./mvnw`
- 可选：Ollama（做本地语义分析）
- 可选：LLM provider key（OpenRouter / Qwen / OpenAI / Gemini 等）

### 本地最小启动

```bash
# 1）先做回归
./mvnw -q test

# 2）创建本地覆盖文件
cp mindos-secrets.local.properties.example mindos-secrets.local.properties

# 3）检查本地配置是否有效
chmod +x ./scripts/unix/local/run-local.sh ./scripts/unix/local/run-release.sh ./scripts/check-secrets.sh
./scripts/check-secrets.sh --mode=local
./scripts/unix/local/run-local.sh --dry-run

# 4）启动服务
./scripts/unix/local/run-local.sh
```

也可以直接启动：

```bash
./mvnw -pl assistant-api -am spring-boot:run \
  -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8
```

### Solo 模式

单用户日常使用推荐 `solo` profile：

```bash
./mvnw -pl assistant-api -am spring-boot:run \
  -Dspring-boot.run.profiles=solo \
  -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8
```

常用脚本：

```bash
./scripts/unix/local/run-mindos-solo.sh
./scripts/unix/local/solo-cli.sh
./scripts/unix/local/solo-smoke.sh
./scripts/unix/local/solo-stop.sh
```

## 使用方法

### 1）公开 Chat API

`POST /chat` 和 `POST /api/chat` 接收：

```json
{
  "userId": "local-user",
  "message": "帮我总结今天的工作",
  "profile": {
    "assistantName": "MindOS",
    "role": "coding-partner",
    "style": "concise",
    "language": "zh-CN",
    "timezone": "Asia/Shanghai",
    "llmProvider": "qwen",
    "llmPreset": "quality"
  }
}
```

当前公开 `profile` 支持的字段只有：

- `assistantName`
- `role`
- `style`
- `language`
- `timezone`
- `llmProvider`
- `llmPreset`

示例：

```bash
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{
    "userId":"local-user",
    "message":"你有哪些技能？",
    "profile":{
      "style":"concise",
      "language":"zh-CN",
      "llmProvider":"qwen",
      "llmPreset":"balanced"
    }
  }'
```

查看历史：

```bash
curl http://localhost:8080/api/chat/local-user/history
```

### 2）CLI

```bash
./mvnw -q -pl mindos-cli -am package
./mvnw -q -pl mindos-cli -am exec:java \
  -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication
```

常见用法：

- 自然语言：`我有哪些技能`
- 自然语言：`帮我拉取最近的记忆`
- slash：`/profile show`
- slash：`/memory pull --since 0 --limit 50`
- slash：`/skills`

参数化启动：

```bash
./mvnw -q -pl mindos-cli -am exec:java \
  -Dexec.mainClass=com.zhongbo.mindos.assistant.cli.MindosCliApplication \
  -Dexec.args="--server http://localhost:8080 --user local-user --theme cyber"
```

### 3）显式 Goal 执行 / Human-AI Co-Runtime

高级 co-runtime 功能目前主要通过内嵌 Goal Runtime 暴露，而不是公开 `/chat` DTO。

示例：

```java
@Autowired
private AgentLoop agentLoop;

AutonomousGoalRunResult result = agentLoop.runGoal(
    "local-user",
    "修复构建、整理发布说明，并在高风险动作前等待我审批",
    Map.of(
        "executionPolicy", "autonomous",
        "runtimeTargetNode", "node:local",
        "human.preference.autonomy", 0.70,
        "human.preference.riskTolerance", 0.35,
        "human.preference.costSensitivity", 0.55,
        "human.preference.style", "concise",
        "human.approval.queue", List.of(
            Map.of("status", "approved", "reason", "可以继续")
        ),
        "human.feedback.queue", List.of(
            Map.of(
                "approved", false,
                "rollback", true,
                "notes", "换成更稳的方案",
                "corrections", Map.of("coruntime.allowedAgentIds", List.of("conservative-planner"))
            )
        )
    )
);
```

返回的 `AutonomousGoalRunResult` 现在会带上：

- `runtimeState`
- `runtimeHistory`
- `sharedDecisions`
- `interventionEvents`
- `humanPreference`
- `trustScore`
- `latestExplanation()`

## 配置文档

### 配置层次

推荐按以下顺序理解配置来源：

1. `assistant-api/src/main/resources/application.properties`：仓库默认值
2. `application-solo.properties` 或其他 profile：profile 级默认值
3. `dist/mindos-windows-server/mindos-secrets.properties`：分发包默认值
4. `mindos-secrets.local.properties`：本地覆盖
5. `mindos-secrets.release.properties`：发布覆盖
6. 环境变量：最终覆盖层

`./scripts/unix/local/run-local.sh` 的加载顺序是：

1. `dist/mindos-windows-server/mindos-secrets.properties`
2. `mindos-secrets.local.properties`（若存在）

`./scripts/unix/local/run-release.sh` 会加载 dist 文件和 `mindos-secrets.release.properties`，并对占位值做严格失败。

### Spring 配置名与环境变量的对应关系

Spring Boot relaxed binding 生效，例如：

| Spring 属性 | 环境变量 |
| --- | --- |
| `mindos.llm.provider-endpoints` | `MINDOS_LLM_PROVIDER_ENDPOINTS` |
| `mindos.dispatcher.semantic-analysis.force-local` | `MINDOS_DISPATCHER_SEMANTIC_ANALYSIS_FORCE_LOCAL` |
| `mindos.coruntime.approval-risk-threshold` | `MINDOS_CORUNTIME_APPROVAL_RISK_THRESHOLD` |

### 主要配置分组

| 关注点 | Spring 前缀 | 常用环境变量前缀 | 作用 |
| --- | --- | --- | --- |
| LLM 传输与路由 | `mindos.llm.*` | `MINDOS_LLM_*` | HTTP 开关、provider map、路由、重试、缓存 |
| Dispatcher 行为 | `mindos.dispatcher.*` | `MINDOS_DISPATCHER_*` | 语义分析、skill 路由、本地升级、提示词收缩 |
| Memory | `mindos.memory.*` | `MINDOS_MEMORY_*` | 文件持久化、状态存储、向量/检索 |
| Human-AI Co-Runtime | `mindos.coruntime.*` | `MINDOS_CORUNTIME_*` | 审批/autonomy 阈值 |
| Skills / MCP | `mindos.skills.*`、`mindos.skill.*` | `MINDOS_SKILLS_*`、`MINDOS_SKILL_*` | MCP server、搜索源、skill 配置 |
| IM 网关 | `mindos.im.*` | `MINDOS_IM_*` | 钉钉 / 飞书 / 企业微信 |
| 安全 | `mindos.security.*` | `MINDOS_SECURITY_*` | 管理 token、危险操作保护 |

### LLM 与 local-first 路由

重点配置：

| 配置项 | 默认值 | 作用 |
| --- | --- | --- |
| `mindos.llm.http.enabled` | `false` | 是否真的发出 HTTP LLM 请求；关闭时走 stub |
| `mindos.llm.provider` | `stub` | 默认 provider alias |
| `mindos.llm.provider-endpoints` | 空 | `provider:url` 映射 |
| `mindos.llm.provider-models` | 空 | `provider:model` 映射 |
| `mindos.llm.provider-keys` | 空 | `provider:key` 映射 |
| `mindos.llm.routing.mode` | `fixed` | `fixed` 或 `auto` |
| `mindos.llm.routing.stage-map` | `llm-dsl:openai,llm-fallback:openai` | stage -> provider |
| `mindos.llm.routing.preset-map` | `cost:openai,balanced:openai,quality:openai` | preset -> provider |
| `mindos.dispatcher.semantic-analysis.enabled` | `true` | 是否启用语义分析 |
| `mindos.dispatcher.semantic-analysis.llm-enabled` | `false` | 是否允许额外 LLM 参与语义分析 |
| `mindos.dispatcher.semantic-analysis.force-local` | `true` | 语义阶段优先本地模型 |
| `mindos.dispatcher.semantic-analysis.llm-provider` | `local` | 语义分析使用的 provider |
| `mindos.dispatcher.semantic-analysis.clarify-min-confidence` | `0.70` | 低置信度澄清阈值 |

### Human-AI Co-Runtime 全局阈值

这些配置是共享控制的核心阈值：

| Spring 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `mindos.coruntime.approval-risk-threshold` | `0.68` | 预测风险超过该值时要求人工审批 |
| `mindos.coruntime.high-cost-threshold` | `0.75` | 预测成本超过该值时要求审批 |
| `mindos.coruntime.approval-confidence-floor` | `0.55` | 置信度低于该值时要求审批 |
| `mindos.coruntime.autonomy-confidence-threshold` | `0.62` | AI 自动执行所需的最小置信度 |
| `mindos.coruntime.min-trust-to-autonomy` | `0.45` | AI 自动执行所需的最小 trust 分数 |

这些值直接由 `ControlProtocol` 读取。它们目前还没有全部出现在 env 模板里，但标准 Spring 环境变量写法已经可用：

```properties
MINDOS_CORUNTIME_APPROVAL_RISK_THRESHOLD=0.70
MINDOS_CORUNTIME_HIGH_COST_THRESHOLD=0.80
MINDOS_CORUNTIME_AUTONOMY_CONFIDENCE_THRESHOLD=0.65
MINDOS_CORUNTIME_MIN_TRUST_TO_AUTONOMY=0.50
```

### 单次 Goal 执行的 runtime attributes

这部分不是全局 Spring 属性，而是 `AgentLoop.runGoal(...)` / `AutonomousLoopEngine.run(...)` 的 `profileContext` 运行时参数。

| Key | 类型 | 含义 |
| --- | --- | --- |
| `executionPolicy` | string | `realtime` / `batch` / `speculative` / `long-running` / `autonomous` |
| `runtimeTargetNode` | string | 初始迁移到指定节点 |
| `human.preference.autonomy` | number `0..1` | 用户偏好的自动化程度 |
| `human.preference.riskTolerance` | number `0..1` | 风险容忍度 |
| `human.preference.costSensitivity` | number `0..1` | 成本敏感度 |
| `human.preference.style` | string | 偏好的决策 / 回复风格 |
| `human.approval.default` | string | 默认审批模式，如 `approved`、`rejected` |
| `human.approval.queue` | list | 预置审批结果队列 |
| `human.feedback.queue` | list | 预置执行后反馈队列 |
| `coruntime.allowedAgentIds` | list/string | 限制 planner agent 选择范围 |
| `coruntime.forceHumanReview` | boolean | 强制进入人工审查 |
| `coruntime.forceHumanOverride` | boolean | 强制走人工覆盖路径 |
| `coruntime.overrideGraph` | `TaskGraph` 对象 | 直接替换计划图，仅限内嵌 Java 使用 |

注意：

- `coruntime.overrideGraph` 只适合内嵌 / 进程内 Java 调用
- 当前公开 `/chat` DTO **不支持**直接传入这些高级 runtime attributes

### 搜索与 MCP 配置

大多数场景建议：

- 搜索类源优先用 `MINDOS_SKILLS_SEARCH_SOURCES`
- 通用 MCP 工具用 `MINDOS_SKILLS_MCP_SERVERS`
- 每个 alias 的鉴权头用 `MINDOS_SKILLS_MCP_SERVER_HEADERS`

重要配置：

| 配置项 | 作用 |
| --- | --- |
| `mindos.skills.search-sources` / `MINDOS_SKILLS_SEARCH_SOURCES` | 统一搜索源映射 |
| `mindos.skills.mcp-servers` / `MINDOS_SKILLS_MCP_SERVERS` | `alias:url` MCP server 映射 |
| `mindos.skills.mcp-server-headers` / `MINDOS_SKILLS_MCP_SERVER_HEADERS` | `alias:Header=value;Header2=value2` |
| `mindos.skills.custom-dir` | 自定义 JSON skills 目录 |
| `mindos.skills.external-jars` | 外部 skill JAR URL |

### 本地最小配置示例

`mindos-secrets.local.properties`：

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

本地 Ollama 快速检查：

```bash
curl http://localhost:11434/api/chat \
  -d '{"model":"gemma3:1b-it-q4_K_M","messages":[{"role":"user","content":"Hello!"}]}'
```

如果这里就失败，先修 Ollama；否则 MindOS 可能看起来已经“路由到 local”，但语义分析阶段仍然无法真正产出。

## 验证与部署辅助

推荐验证命令：

```bash
./scripts/check-secrets.sh --mode=local
./scripts/check-secrets.sh --mode=release
./scripts/unix/local/run-local.sh --dry-run
./scripts/unix/local/run-release.sh --dry-run
./mvnw -q test
```

云端辅助脚本：

```bash
chmod +x ./scripts/unix/cloud/*.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/init-authorized-keys.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/cloud-init.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/deploy-cloud.sh
CLOUD_HOST=1.2.3.4 CLOUD_USER=root ./scripts/unix/cloud/cloud-check.sh
```

## 说明

- 只有在 `mindos.llm.http.enabled=true` 且 provider map / key 配好之后，才会真的发出 LLM HTTP 请求。
- 未配置 JDBC `DataSource` 时，MindOS 默认使用文件存储维持重启后的记忆连续性。
- 运行测试时，`assistant-api/data/*` 目录下的 memory-state 和 H2 文件可能会变化。
- 当前公开 REST DTO 仍然偏保守；高级 Human-AI Co-Runtime 控制参数主要面向内嵌 / 内部 goal runtime，而不是外部 `/chat` 客户端。
