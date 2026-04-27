# Delphi Agent Framework

`delphi-agent` 是一个面向平台侧 Agent 后端的 Java 框架，基于多模块 Maven 工程实现，核心能力包括：

- 基于 ReAct 的 Agent loop：模型流式推理、工具调用、工具结果回灌、继续推理。
- 会话运行时：MongoDB 持久化、会话树、fork、navigate、compact、steer、abort。
- 多租户隔离：`tenantId == namespace` 的运行时校验、配额、审计、用量统计。
- Sandbox 执行：默认 Docker 隔离，开发模式支持 local-dev 本地隔离。
- 工具系统：内置文件/命令工具、Skill 工具、Subagent 编排工具、租户工具策略。
- Catalog：按目录加载 skills、prompts、resources，并支持 namespace 可见性。
- Provider 抽象：通过 `AiRuntime` + `ApiProvider` + `ModelCatalog` 接入模型。

主要技术架构图见 [ARCHITECHE.MD](./ARCHITECHE.MD)，API 文档见 [DOCS.md](./DOCS.md)。

## 工程结构

| 模块 | 职责 |
| --- | --- |
| `delphi-agent-ai-api` | 模型、消息、内容块、工具定义、流式事件、`AiRuntime`/provider SPI。 |
| `delphi-agent-springai-provider` | Spring AI 适配层，当前 reference server 通过 Anthropic 兼容接口接入 DeepSeek。 |
| `delphi-agent-core` | Agent loop、AgentState、AgentTool、工具参数校验、事件模型、steering/follow-up 队列。 |
| `delphi-agent-runtime` | 会话运行时、Mongo 持久化、sandbox、catalog、内置工具、skills、subagent、工具策略、配额和审计。 |
| `delphi-agent-http-api` | `/api/chat/stream`、catalog、audit、usage 等 HTTP/SSE API。 |
| `delphi-agent-server` | 可运行 Spring Boot reference server 和默认 provider/model 配置。 |

## 架构文档

[ARCHITECHE.MD](./ARCHITECHE.MD) 集中维护主要技术架构图：

- 整体架构流程图
- 请求运行时序图
- Agent loop 技术流程图
- 工具与 skills 技术架构图
- Sandbox 技术架构图
- Catalog、会话持久化和部署运行视图

## 快速开始

### 环境要求

- JDK 21
- Maven 3.9+
- MongoDB 5+
- Docker（默认 profile 需要；`local-dev` profile 不需要）
- `DEEPSEEK_API_KEY`

如需切换 JDK，可在本机使用 `jdk8`、`jdk17`、`jdk21`。

### 构建

```bash
mvn -DskipTests compile
```

### 配置

可以在项目根目录创建 `.env`：

```properties
PORT=8080
MONGODB_URI=mongodb://localhost:27017/pi-agent-framework
DEEPSEEK_API_KEY=sk-your-key

PI_AGENT_SKILLS_DIRS=./skills,${HOME}/.codex/skills
PI_AGENT_PROMPTS_DIRS=./prompts
PI_AGENT_RESOURCES_DIRS=./resources
PI_WORKSPACES_ROOT=./workspaces

PI_TOOLS_BUILTIN_ENABLED=true
PI_TOOL_POLICY_ORCHESTRATOR_STRICT=false
```

### 启动

默认 Docker sandbox：

```bash
mvn -pl delphi-agent-server spring-boot:run
```

开发模式，本地进程执行：

```bash
mvn -pl delphi-agent-server spring-boot:run -Dspring-boot.run.profiles=local-dev
```

默认服务地址：`http://localhost:8080`。

### 验证

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/catalog/models
```

## HTTP API

API 入口、请求头、SSE 事件和命令清单集中维护在 [DOCS.md](./DOCS.md)，完整接口说明见 [docs/api-reference.zh-CN.md](./docs/api-reference.zh-CN.md)。

## Agent Loop

核心实现位于 `delphi-agent-core` 的 `Agent.runLoop()`。它是一个 turn-based ReAct 循环：

```text
user prompt / steering / follow-up
  -> streamAssistantResponse()
  -> LLM 输出文本和 tool calls
  -> executeToolCalls()
  -> tool result message 回灌上下文
  -> 如果还有 tool calls 或 steering，继续下一 turn
  -> 如果没有 tool calls，检查 follow-up
  -> AgentEnd
```

关键机制：

- `AgentState` 持有模型、system prompt、messages、tools、streaming 状态和 pending tool calls。
- `AiRuntime.streamSimple()` 负责把 `AgentMessage` 转成 provider 消息并流式返回。
- `AgentEvent` 暴露 `MessageStart/Update/End`、`ToolExecutionStart/Update/End`、`TurnEnd`、`AgentEnd` 等内部事件。
- `ToolArgumentValidator` 在工具执行前按 JSON Schema 校验模型生成的参数。
- `ToolExecutionMode` 支持同一轮多个 tool call 的 `PARALLEL` 或 `SEQUENTIAL` 执行。
- `QueueMode` 支持 steering/follow-up 队列按 `ALL` 或 `ONE_AT_A_TIME` 消费。

`AgentRunRuntime` 在 core loop 之外增加平台运行时语义：

- `RunQueueMode.INTERRUPT`：同 session 有 active run 时中断旧 run 并运行新 run。
- `FOLLOWUP`：排队等待当前 run 完成后执行。
- `STEER`：把新 prompt 作为 steering 注入当前 run。
- `DROP`：丢弃新 run。
- `REJECT`：直接拒绝。

## Sandbox

所有命令型能力都通过 `ExecutionBackend` 抽象进入 workspace：

```text
ExecutionContext(namespace, sessionId, userId)
  -> ExecutionBackend.execute/readFile/writeFile
  -> workspaces/<namespace>/<sessionId>
```

### DockerIsolatedBackend

默认 profile 使用 Docker backend。每次命令通过 `docker run --rm` 执行，主要约束：

- `--network none` 禁用网络。
- `--user 65534:65534` 使用非 root 用户。
- `--read-only` 只读 rootfs。
- `-v <workspace>:/workspace:rw` 只挂载当前 session workspace。
- `-w /workspace` 固定工作目录。
- `--memory`、`--cpu-quota`、`--pids-limit` 做资源限制。
- `--tmpfs /tmp:rw,noexec,nosuid,size=64m` 限制临时目录。

Docker 资源限制默认来自配置，也会被 `TenantQuotaManager` 的租户配额覆盖。

### LocalIsolatedBackend

`local-dev` profile 使用本地 `ProcessBuilder("bash", "-c", command)`，工作目录固定到 session workspace。它保留路径校验和超时控制，但没有容器级进程、网络、文件系统和用户隔离，只适合本地开发。

### 路径和 workspace 安全

- namespace/sessionId 会拒绝 `..`、`/`、`\`、空字节等路径穿越字符。
- `readFile`、`writeFile` 和内置工具都会把路径 normalize 后验证仍位于 workspace 内。
- workspace 结构固定为 `workspaces/<namespace>/<sessionId>`。
- `SkillAgentTool` 会把 skill 目录复制到 workspace 的 `.skills/<skill-name>` 后再执行。

## 工具系统

`AgentToolFactory` 通过 `ToolInventory` 收集工具，再经过 `ToolPolicyPipeline` 输出给 Agent。工具来源包括内置工具、skills、subagent 编排工具和 `TaskPlanningTool`。

### 内置工具

默认可用工具由 `pi.tools.builtin` 配置：

| 工具 | 类别 | 说明 |
| --- | --- | --- |
| `read` | `READONLY` | 读取 workspace 内文件，支持 offset/limit 和输出截断。 |
| `grep` | `READONLY` | 在 workspace 中搜索文本。 |
| `find` | `READONLY` | 按 glob 查找文件。 |
| `ls` | `READONLY` | 列目录。 |
| `write` | `MUTATING` | 写入或覆盖 workspace 内文件。 |
| `edit` | `MUTATING` | 基于精确文本替换编辑文件。 |
| `bash` | `EXECUTABLE` | 在 sandbox/workspace 中执行 shell 命令。 |

默认配置中 `available` 是 `[read, bash, edit, write, grep, find, ls]`，`default-enabled` 是 `[read, bash, edit, write]`；当前运行时 inventory 会收集 `available` 列表，再交给工具策略裁剪或包装。

### 工具策略

工具策略按角色限制工具类别：

| 角色 | 默认允许 |
| --- | --- |
| `ORCHESTRATOR` | 默认允许全部；开启 `pi.tool-policy.orchestrator-strict=true` 后只允许 `READONLY`、`INSTRUCTIONAL`、`ORCHESTRATION`。 |
| `PLANNER` / `RESEARCHER` | `READONLY`、`INSTRUCTIONAL`。 |
| `REVIEWER` | `READONLY`。 |
| `CODER` | `READONLY`、`MUTATING`、`EXECUTABLE`、`INSTRUCTIONAL`。 |
| `TESTER` | `READONLY`、`EXECUTABLE`、`INSTRUCTIONAL`。 |

额外规则：

- `tenantId` 与 `namespace` 不一致时拒绝工具执行。
- subagent 编排工具只能由 `ORCHESTRATOR` 使用。
- subagent 最大深度为 2。
- `TESTER` 不允许 mutating 工具。
- 被拒绝的工具会被包装成 deny 工具，调用时返回策略拒绝原因，同时记录审计。

### Subagent 工具

主 Agent 作为 `ORCHESTRATOR` 时会获得：

- `subagent_spawn`：创建 `planner`、`coder`、`reviewer`、`tester`、`researcher` 子 Agent。
- `subagent_status`：查询子 Agent 状态。
- `subagent_result`：读取子 Agent 结果。
- `subagent_abort`：中止子 Agent。

子 Agent 与父 run 共享 tenant/session 语义，可选择 `session`、`project`、`ephemeral` workspace scope。每个 parent run 默认最多 8 个 active subagent。

## Skills

Skills 是目录级能力单元，每个 skill 目录包含一个 `SKILL.md`。

```text
skills/
  public/
    task-planning/
      SKILL.md
  namespaces/
    demo/
      build-react/
        SKILL.md
        build.sh
```

可见性规则：

- `skills/public/**` 对所有 namespace 可见。
- `skills/namespaces/<namespace>/**` 只对对应 namespace 可见。
- 同名 namespace skill 会覆盖 public skill。
- `SkillsResolver` 会按 namespace 缓存解析结果，`POST /api/catalog/reload` 会重扫磁盘并清理缓存。

`SKILL.md` 支持 YAML frontmatter：

```markdown
---
description: "构建 React 项目"
entrypoint: "./build.sh"
args_schema: '{"type":"object","properties":{"target":{"type":"string"}}}'
timeout_ms: 120000
---
# Build React

执行前端构建并返回结果。
```

字段说明：

- `description`：工具描述；缺省时使用正文第一条非空行。
- `entrypoint`：可执行脚本路径，必须是 `./` 开头的相对路径。
- `args_schema`：JSON Schema 字符串，用于生成工具参数并做基础类型校验。
- `timeout_ms`：可执行 skill 的超时时间；缺省 30 秒。

两种 skill 类型：

- 指令型 skill：没有 `entrypoint`，调用时把 skill 内容作为工具结果回灌给模型。
- 可执行 skill：有 `entrypoint`，调用时复制 skill 目录到 workspace 的 `.skills/<skill-name>`，通过 `ExecutionBackend` 在 sandbox 中执行，并把结构化参数通过命令参数或 `PI_SKILL_ARGS_JSON` 环境变量传入。

## Catalog、Prompts 和 Resources

配置项：

```yaml
pi:
  resources:
    skills-dirs: ./skills,~/.codex/skills
    prompts-dirs: ./prompts
    resources-dirs: ./resources
```

环境变量：

```bash
PI_AGENT_SKILLS_DIRS=./skills,/opt/skills
PI_AGENT_PROMPTS_DIRS=./prompts
PI_AGENT_RESOURCES_DIRS=./resources
```

加载规则：

- skills：扫描 `SKILL.md`。
- prompts：扫描 `.prompt.md`、`.prompt.txt`、`.prompt`。
- resources：扫描普通文件，排除 `SKILL.md` 和 prompt 文件。
- reload：调用 `POST /api/catalog/reload` 后对后续请求生效，不会替换正在执行的 run。

## 会话、持久化和上下文管理

`AgentSessionRuntime` 负责 session 生命周期：

- `createSession`：创建 Mongo session，并初始化 live Agent。
- `prompt` / `cont`：通过 `SessionPromptQueue` 串行化同 session prompt。
- `steer` / `followUp` / `abort`：运行时控制。
- `forkSession` / `navigateTree`：会话树分支和导航。
- `compact`：将旧消息压缩为摘要，并保留最近消息。
- `persistConversation`：把消息保存为 `SessionEntryDocument`，维护 `headEntryId`。

上下文压缩默认配置：

```yaml
pi:
  compaction:
    context-window-ratio: 0.8
    max-messages: 60
    default-keep-count: 20
```

当 LLM 摘要失败时，会回退到启发式摘要。

## 多租户、配额、审计和用量

运行时边界：

- HTTP 请求头 `X-Tenant-Id` 必须存在。
- body 中 `namespace` 必须与 `X-Tenant-Id` 一致。
- session 查询使用 `sessionId + namespace` 绑定校验。
- 工具策略再次校验 `tenantId == namespace`。

配额：

- `TenantRuntimeGuard` 控制租户和用户并发 run。
- 同 session queue 默认最多 20 个 queued run。
- `SubagentQuotaGuard` 控制每个租户和 parent run 的 subagent 数量。
- Docker CPU/memory/PIDs 可按租户覆盖。

审计和用量：

- `AuditService`/`RuntimeAuditService` 记录 session、prompt、abort、queue、subagent、工具策略等事件。
- `UsageMeteringService` 根据模型 usage 记录 token 用量。
- `/api/audit` 和 `/api/usage` 提供查询入口。

## 模型和 Provider

reference server 当前从 `pi.models` 注册模型。默认配置：

```yaml
pi:
  models:
    - id: deepseek-v4-pro
      api: spring-ai-deepseek
      provider: deepseek
      base-url: https://api.deepseek.com/anthropic
      reasoning: false
    - id: deepseek-v4-flash
      api: spring-ai-deepseek
      provider: deepseek
      base-url: https://api.deepseek.com/anthropic
      reasoning: false
  defaults:
    provider: deepseek
    model-id: deepseek-v4-pro
```

`ApiKeyBridgeConfiguration` 会把 `.env`/Spring Environment 中 `pi.api-keys` 配置的 key 桥接到 System properties。当前默认 key 列表为 `DEEPSEEK_API_KEY`。

扩展新 provider 的路径：

1. 实现 `ApiProvider`。
2. 注册为 Spring bean，让 `ApiProviderRegistry` 自动收集。
3. 在 `pi.models` 中添加模型配置，确保 `api` 与 provider id 对应。

## 扩展点

### 自定义工具

实现 `AgentTool`，并通过自定义 `ToolInventory`/factory 或扩展现有工具收集链路注入：

```java
public interface AgentTool {
    String name();
    String label();
    String description();
    Map<String, Object> parametersSchema();
    CompletableFuture<AgentToolResult> execute(
        String toolCallId,
        Map<String, Object> params,
        AgentToolUpdateCallback onUpdate,
        CancellationException cancellation
    );
}
```

### AgentExtension

实现 `AgentExtension` 可挂接：

- `beforeNewSession`
- `beforeFork`
- `beforeNavigateTree`
- `beforeCompact`
- `onAgentEvent`
- `slashCommands`

扩展以 Spring bean 形式自动发现。

## 开发命令

```bash
# 编译
mvn -DskipTests compile

# 全量测试
mvn test

# 指定模块测试
mvn -pl delphi-agent-runtime test

# 启动 reference server
mvn -pl delphi-agent-server spring-boot:run

# 本地 sandbox profile
mvn -pl delphi-agent-server spring-boot:run -Dspring-boot.run.profiles=local-dev
```
