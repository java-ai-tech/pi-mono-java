# delphi-agent

pi-mono agent framework 的 Java 实现。一个多模型、可扩展的 Agentic AI 框架，提供 turn-based agent loop、tool 编排、会话持久化和流式响应能力。

## 项目简介

delphi-agent 是一个面向 LLM Agent 应用的 Java 后端框架，核心设计目标：

- **多 Provider 统一接入** — 通过 SPI 机制对接 OpenAI、Anthropic、Google 及任意 OpenAI 兼容 API（DeepSeek、Groq、xAI、OpenRouter 等）
- **Agent 运行时** — 基于 turn loop 的 agent 执行引擎，支持 tool calling、steering/follow-up 消息队列、自动重试
- **多租户隔离** — namespace 级数据隔离、API 强制 namespace 参数、Skills 可见性控制
- **执行沙箱** — Docker 容器级进程隔离（默认）或本地目录隔离（开发模式），路径穿越防护
- **会话持久化** — MongoDB-first 的会话树存储，支持 fork、navigate、context compaction
- **流式输出** — 原生 SSE 流式事件推送，每个 provider 独立实现 delta 解析
- **可扩展** — Extension 钩子、Resource Catalog（skills/prompts/resources）、Spring Boot Starter 集成

## 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                        Client (Web / CLI)                       │
└──────────────────────────────┬──────────────────────────────────┘
                               │ HTTP / SSE (namespace required)
┌──────────────────────────────▼──────────────────────────────────┐
│                   delphi-coding-agent-server                        │
│              (Spring Boot Application, :8080)                   │
├─────────────────────────────────────────────────────────────────┤
│  delphi-coding-agent-http-api          delphi-coding-agent-sdk     │
│  ┌─────────────────────┐           ┌──────────────────────┐    │
│  │ AgentSessionCtrl    │           │ PiCodingAgentSdk     │    │
│  │ RpcController       │──────────▶│ AgentSessionHandle   │    │
│  │ (REST + SSE + RPC)  │           └──────────┬───────────┘    │
│  └─────────────────────┘                      │                │
├───────────────────────────────────────────────▼─────────────────┤
│                  delphi-coding-agent-core                      │
│  ┌──────────────────┐  ┌──────────────┐  ┌────────────────┐   │
│  │AgentSessionRuntime│  │ExtensionRuntime│ │ResourceCatalog │   │
│  │  (会话生命周期)    │  │  (扩展钩子)    │  │ (技能/提示/资源)│   │
│  └────────┬─────────┘  └──────────────┘  └────────────────┘   │
│           │                                                     │
│  ┌────────▼─────────┐  ┌──────────────────────────────────┐   │
│  │    MongoDB        │  │  delphi-agent-core              │   │
│  │ (Sessions/Entries)│  │  ┌──────┐ ┌─────────┐ ┌──────┐  │   │
│  └──────────────────┘  │  │Agent │ │AgentState│ │Tools │  │   │
│                         │  │(Loop)│ │(Messages)│ │(SPI) │  │   │
│                         │  └──┬───┘ └─────────┘ └──────┘  │   │
│                         └─────┼────────────────────────────┘   │
├───────────────────────────────▼─────────────────────────────────┤
│                     delphi-ai-spi                              │
│            ┌──────────────────────────────┐                    │
│            │  AiRuntime (Provider 调度)    │                    │
│            │  ApiProviderRegistry         │                    │
│            │  ModelCatalog                │                    │
│            └──────────────┬───────────────┘                    │
├───────────────────────────▼─────────────────────────────────────┤
│                    delphi-ai-api                               │
│     Model · Message · ContentBlock · ToolDefinition             │
│     StreamOptions · AssistantMessageEvent · Usage               │
├─────────────────────────────────────────────────────────────────┤
│                      Providers (Spring AI)                      │
│       ┌──────────────────────────────────────────────┐         │
│       │  delphi-ai-provider-springai                │         │
│       │  SpringAiChatModelProvider (Flux→Flow 桥接)   │         │
│       └──────────────────┬───────────────────────────┘         │
│  ┌───────────┐  ┌────────┴───────┐  ┌──────────────────────┐  │
│  │  OpenAI    │  │   DeepSeek     │  │   ZhiPu (GLM)        │  │
│  │(Spring AI) │  │  (OpenAI兼容)   │  │  (Spring AI Starter) │  │
│  └───────────┘  └────────────────┘  └──────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## 模块说明

| 模块 | 职责 |
|------|------|
| `delphi-ai-api` | 统一的模型/消息/事件契约定义（Model、Message、ContentBlock、ToolDefinition 等） |
| `delphi-ai-spi` | Provider 注册表、运行时调度（AiRuntime、ApiProviderRegistry、ModelCatalog） |
| `delphi-ai-provider-springai` | Spring AI 适配层，将 ChatModel 包装为 ApiProvider，Flux→Flow 桥接 |
| `delphi-agent-core` | Agent 运行时核心：turn loop、tool 编排（并行/串行）、steering/follow-up 队列 |
| `delphi-coding-agent-core` | 会话运行时、MongoDB 持久化、Extension 钩子、Resource Catalog、Context Compaction |
| `delphi-coding-agent-sdk` | 可嵌入的 Java SDK（`PiCodingAgentSdk` / `AgentSessionHandle`） |
| `delphi-coding-agent-http-api` | REST + SSE + RPC 控制器层 |
| `delphi-coding-agent-spring-boot-starter` | Spring Boot 自动配置 Starter |
| `delphi-coding-agent-server` | 可运行的 Spring Boot 服务端应用 |
| `delphi-bom` | 依赖版本管理 BOM |

## 技术栈

- **Java 21** — sealed interface、record、pattern matching
- **Spring Boot 3.4.1** — Web、Data MongoDB、Actuator
- **Spring AI 1.0.1** — 统一的 LLM 调用抽象（ChatModel、ChatClient）
- **MongoDB 5.x+** — 会话与对话树持久化
- **Jackson 2.17.2** — JSON 序列化
- **Maven** — 多模块构建
- **SSE (Server-Sent Events)** — 实时流式事件推送

## 支持的模型

| Provider | 模型 | API 适配 |
|----------|------|----------|
| OpenAI | gpt-4o-mini | spring-ai-openai |
| DeepSeek | deepseek-chat | spring-ai-deepseek (OpenAI 兼容) |
| DeepSeek | deepseek-reasoner | spring-ai-deepseek (OpenAI 兼容) |
| 智谱 (ZhiPu) | GLM-4 | spring-ai-zhipuai |
| 智谱 (ZhiPu) | GLM-4-Flash | spring-ai-zhipuai |

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.9+
- MongoDB 5.x+（本地或远程）
- 至少一个 LLM Provider 的 API Key

### 1. 克隆与构建

```bash
git clone <repo-url>
cd delphi-agent
mvn -q package
```

### 2. 配置环境变量

复制 `.env.example` 并填入你的 API Key：

```bash
cp .env.example .env
```

`.env` 文件内容：

```properties
PORT=8080
MONGODB_URI=mongodb://localhost:27017/delphi-agent-framework

# 至少配置一个 Provider 的 Key
DEEPSEEK_API_KEY=sk-your-deepseek-key
ZHIPUAI_API_KEY=your-zhipuai-key
# OPENAI_API_KEY=sk-your-openai-key
```

### 3. 启动服务

```bash
set -a && source .env && set +a

# 生产模式（Docker 沙箱隔离，需要 Docker 运行中）
mvn -q -pl delphi-coding-agent-server spring-boot:run

# 本地开发模式（目录隔离，无需 Docker）
mvn -q -pl delphi-coding-agent-server spring-boot:run -Dspring-boot.run.profiles=local-dev
```

服务默认监听 `http://localhost:8080`。

### 4. 验证

```bash
# 健康检查
curl http://localhost:8080/actuator/health

# 查看可用模型
curl http://localhost:8080/api/sessions/models
```

## 基础用例

### 用例 1：直接流式聊天（推荐）

**一次性调用，无需创建 Session，直接流式输出结果，支持任务规划工具自动调用**

#### 可视化界面

访问 http://localhost:8080 打开可视化聊天界面，支持：
- 实时流式响应展示
- 工具调用过程可视化
- Agent Loop 执行流程展示
- 多模型切换（DeepSeek、GLM、OpenAI）

#### API 调用示例

```bash
# 使用 DeepSeek 模型进行流式聊天
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "my-tenant",
    "prompt": "用 Java 写一个快速排序算法",
    "provider": "deepseek",
    "modelId": "deepseek-chat",
    "systemPrompt": "你是一个专业的编程助手",
    "temperature": 0.7,
    "maxTokens": 4096
  }'

# 使用 GLM 模型
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "my-tenant",
    "prompt": "解释一下 ReAct 架构",
    "provider": "zhipuai",
    "modelId": "glm-4-flash"
  }'

# 使用 OpenAI 模型
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "my-tenant",
    "prompt": "What is the capital of France?",
    "provider": "openai",
    "modelId": "gpt-4o-mini"
  }'
```

**SSE 事件流格式**：
```
event: agent_start
data: {"type":"agent_start"}

event: turn_start
data: {"type":"turn_start"}

event: message_start
data: {"type":"message_start","role":"assistant"}

event: text
data: {"type":"text","delta":"我来"}

event: text
data: {"type":"text","delta":"帮你"}

event: tool_start
data: {"type":"tool_start","toolName":"task_planning","toolCallId":"call_123"}

event: tool_end
data: {"type":"tool_end","toolName":"task_planning","toolCallId":"call_123","result":"📋 任务规划结果...","isError":false}

event: turn_end
data: {"type":"turn_end"}

event: done
data: {"type":"done"}
```

**内置工具**：
- `task_planning` - 任务规划工具，自动将复杂任务分解为可执行步骤

### 用例 2：通过 HTTP API 进行对话（Session 模式）

```bash
# 1) 创建会话
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "projectKey": "my-project",
    "sessionName": "demo",
    "provider": "deepseek",
    "modelId": "deepseek-chat"
  }' | jq -r '.sessionId')

echo "Session: $SESSION_ID"

# 2) 发送 prompt（异步，立即返回 accepted）
curl -s -X POST "http://localhost:8080/api/sessions/$SESSION_ID/prompt" \
  -H "Content-Type: application/json" \
  -d '{"message": "用 Java 写一个快速排序"}'

# 3) 订阅 SSE 事件流（另一个终端）
curl -N "http://localhost:8080/api/sessions/$SESSION_ID/events"

# 4) 获取最终状态
curl -s "http://localhost:8080/api/sessions/$SESSION_ID/state" | jq
```

### 用例 2：通过 Java SDK 编程调用

```java
@Autowired
private PiCodingAgentSdk sdk;

// 创建会话（namespace 必填）
var handle = sdk.createSession(new CreateAgentSessionOptions(
    "my-tenant",                // namespace（租户标识）
    "my-project",               // projectKey
    "code-review-session",      // sessionName
    "anthropic",                // provider
    "claude-sonnet",            // modelId
    "你是一个代码审查助手"         // systemPrompt
));

// 订阅事件
sdk.subscribeEvents(handle.sessionId(), event -> {
    System.out.println("Event: " + event.getClass().getSimpleName());
});

// 发送 prompt 并等待完成
handle.prompt("请审查以下代码的安全性：\n```java\n...\n```").join();

// 获取回复
String reply = handle.lastAssistantText();
System.out.println(reply);
```

### 用例 3：Steering 和 Follow-up

```bash
# 在 agent 处理过程中插入 steering 消息（会在当前 turn 结束后注入）
curl -s -X POST "http://localhost:8080/api/sessions/$SESSION_ID/steer" \
  -H "Content-Type: application/json" \
  -d '{"message": "请用中文回答，并给出时间复杂度分析"}'

# agent 完成后追加 follow-up（触发新一轮对话）
curl -s -X POST "http://localhost:8080/api/sessions/$SESSION_ID/follow-up" \
  -H "Content-Type: application/json" \
  -d '{"message": "能否改成非递归版本？"}'
```

### 用例 4：Context Compaction

```bash
# 当对话过长时，压缩历史上下文（保留最近 10 条消息）
curl -s -X POST "http://localhost:8080/api/sessions/$SESSION_ID/compact" \
  -H "Content-Type: application/json" \
  -d '{"keepRecentMessages": 10}'
```

### 用例 5：会话 Fork

```bash
# 从某个对话节点 fork 出新会话
curl -s -X POST "http://localhost:8080/api/sessions/$SESSION_ID/fork" \
  -H "Content-Type: application/json" \
  -d '{"sessionName": "探索另一种方案"}'
```

## 核心概念

### Agent Loop

Agent 的核心执行流程：

```
用户 Prompt ──▶ LLM 流式响应 ──▶ 提取 Tool Calls ──▶ 执行 Tools ──▶ 收集结果
                     ▲                                                  │
                     │              检查 Steering 队列                   │
                     └──────────────────────────────────────────────────┘
                                        │
                                  无 Tool Call？
                                        │
                                  检查 Follow-up 队列
                                        │
                                  队列为空 → 结束
```

### Tool 接口

实现 `AgentTool` 接口来注册自定义工具：

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

通过 `agent.state().tools(List.of(myTool1, myTool2))` 注册到 Agent。

### Extension 扩展

实现 `AgentExtension` 接口来挂载生命周期钩子：

```java
public interface AgentExtension {
    ExtensionDecision beforeNewSession(CreateSessionCommand command);
    ExtensionDecision beforeFork(String sessionId, String entryId, String newSessionName);
    ExtensionDecision beforeCompact(String sessionId, Integer keepRecentMessages);
    void onAgentEvent(String sessionId, AgentEvent event);
    List<SlashCommandInfo> slashCommands();
}
```

### 消息队列语义

- **Steering** — 在 agent 执行过程中注入消息，当前 turn 结束后立即处理
- **Follow-up** — agent 完成所有 tool call 后追加新一轮对话
- **QueueMode** — `ALL`（一次性消费全部队列）或 `ONE_AT_A_TIME`（逐条消费）

## HTTP API 参考

### Session API (`/api/sessions`)

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/sessions` | 创建会话 |
| GET | `/api/sessions?projectKey=<key>` | 列出项目下的会话 |
| GET | `/api/sessions/models` | 查看可用模型 |
| GET | `/api/sessions/{id}/state` | 获取会话状态 |
| GET | `/api/sessions/{id}/messages` | 获取消息列表 |
| GET | `/api/sessions/{id}/tree` | 获取对话树 |
| POST | `/api/sessions/{id}/prompt` | 发送 prompt |
| POST | `/api/sessions/{id}/continue` | 继续对话 |
| POST | `/api/sessions/{id}/steer` | 注入 steering 消息 |
| POST | `/api/sessions/{id}/follow-up` | 追加 follow-up |
| POST | `/api/sessions/{id}/compact` | 压缩上下文 |
| POST | `/api/sessions/{id}/abort` | 中止当前操作 |
| POST | `/api/sessions/{id}/model` | 切换模型 |
| POST | `/api/sessions/{id}/thinking-level` | 设置思考级别 |
| POST | `/api/sessions/{id}/fork` | Fork 会话 |
| POST | `/api/sessions/{id}/navigate` | 导航对话树 |
| GET | `/api/sessions/{id}/events` | SSE 事件流 |

### RPC API (`/api/rpc`)

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/rpc/command` | 执行 RPC 命令 |
| GET | `/api/rpc/events/{sessionId}` | SSE 事件流 |
| GET | `/api/rpc/catalog/skills` | 列出 skills |
| GET | `/api/rpc/catalog/prompts` | 列出 prompts |
| GET | `/api/rpc/catalog/resources` | 列出 resources |
| POST | `/api/rpc/catalog/reload` | 重新加载 catalog |

### RPC 命令列表

- **对话**: `prompt`, `steer`, `follow_up`, `abort`, `new_session`
- **状态**: `get_state`
- **模型**: `set_model`, `cycle_model`, `get_available_models`
- **思考**: `set_thinking_level`, `cycle_thinking_level`
- **队列**: `set_steering_mode`, `set_follow_up_mode`
- **压缩/重试**: `compact`, `set_auto_compaction`, `set_auto_retry`, `abort_retry`
- **Shell**: `bash`, `abort_bash`
- **会话**: `get_session_stats`, `export_html`, `switch_session`, `fork`, `set_session_name`
- **消息**: `get_messages`, `get_commands`

## Resource Catalog

框架支持从文件系统加载 skills、prompts 和 resources，可通过环境变量或配置文件指定目录：

| 配置项 | 环境变量 | 默认值 |
|--------|----------|--------|
| `pi.resources.skills-dirs` | `DELPHI_AGENT_SKILLS_DIRS` | `./skills,~/.codex/skills` |
| `pi.resources.prompts-dirs` | `PI_AGENT_PROMPTS_DIRS` | `./prompts` |
| `pi.resources.resources-dirs` | `PI_AGENT_RESOURCES_DIRS` | `./resources` |

支持运行时通过 `POST /api/rpc/catalog/reload` 热重载。

## MongoDB 数据模型

| Collection | 说明 |
|------------|------|
| `agent_sessions` | 会话元数据（模型、配置、head 指针等），按 `{namespace, projectKey, updatedAt}` 索引 |
| `agent_session_entries` | 对话条目（消息、compaction 记录），通过 parentId 构成树结构 |

所有会话查询均通过 `namespace` 过滤，不存在跨租户数据泄露路径。

## Provider API Key 配置

| Provider | 环境变量 | 配置属性 |
|----------|----------|----------|
| OpenAI | `OPENAI_API_KEY` | `spring.ai.openai.api-key` |
| DeepSeek | `DEEPSEEK_API_KEY` | — (通过 ApiKeyBridge 桥接) |
| 智谱 (ZhiPu) | `ZHIPUAI_API_KEY` | `spring.ai.zhipuai.api-key` |

## 多租户隔离

所有 API 和 RPC 接口强制要求 `namespace` 参数，实现租户级数据和执行隔离。

### Namespace 规则

- 所有 HTTP/RPC 请求必须携带 `namespace`，缺失则拒绝
- 会话（session）创建时绑定 namespace，后续操作校验一致性
- 跨 namespace 访问会被拦截并返回错误

### Skills 可见性

Skills 按 namespace 隔离，目录结构：

```
skills/
├── public/                    # 所有 namespace 可见
│   └── shared-tool/SKILL.md
└── namespaces/
    ├── tenant-a/              # 仅 tenant-a 可见
    │   └── private-tool/SKILL.md
    └── tenant-b/
        └── another-tool/SKILL.md
```

Skill 支持两种执行模式：
- **指令型**（无 entrypoint）— 内容作为 agent 指令注入
- **可执行型**（有 entrypoint）— 通过 ExecutionBackend 在沙箱中执行脚本

可执行 Skill 的 SKILL.md 示例：
```markdown
---
entrypoint: "./deploy.sh"
args_schema: '{"type":"object","properties":{"env":{"type":"string"}}}'
---
# Deploy
Deploys the application.
```

### API 示例

```bash
# 查询 namespace 可见的 skills
curl http://localhost:8080/api/rpc/catalog/skills?namespace=my-tenant

# 创建会话（namespace 必填）
curl -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{"type":"new_session","namespace":"my-tenant","projectKey":"proj","provider":"deepseek","modelId":"deepseek-chat"}'
```

## 执行隔离

命令执行通过 `ExecutionBackend` 接口，提供两种实现：

| Spring Profile | 后端 | 隔离级别 | 适用场景 |
|----------------|------|----------|----------|
| 默认（无 profile） | `DockerIsolatedBackend` | 容器级 | 生产环境 |
| `local-dev` | `LocalIsolatedBackend` | 目录级 | 本地开发 |

### 生产环境（Docker 隔离，默认）

```bash
java -jar delphi-coding-agent-server.jar
```

Docker 安全策略：
- `--network none` 禁止网络
- `--user 65534:65534` 非 root 执行
- `--read-only` 只读根文件系统
- CPU / 内存 / pids 资源限制
- 仅挂载当前 namespace/session 的 workspace

### 本地开发（目录隔离）

```bash
java -jar delphi-coding-agent-server.jar --spring.profiles.active=local-dev
```

### 配置

```yaml
pi:
  execution:
    workspaces-root: /data/workspaces    # 工作空间根目录
    docker:
      image: ubuntu:22.04                # 沙箱镜像
      cpu-quota: 50000                   # CPU 配额
      memory-limit: 256m                 # 内存限制
      pids-limit: 100                    # 进程数限制
```

## 项目状态

已实现：

- Spring AI 统一 Provider 适配（OpenAI / DeepSeek / 智谱 GLM）
- Flux → Flow.Publisher 流式桥接
- Agent turn loop 与 tool 编排（并行/串行）
- Steering / Follow-up 消息队列
- MongoDB 会话持久化与对话树（fork / navigate）
- LLM-first context compaction 与分支重写
- Extension 运行时钩子与事件转发
- 文件系统 Resource Catalog（skills / prompts / resources）
- HTTP REST + SSE + RPC 命令协议
- Spring Boot Starter 自动配置
- Actuator 健康检查与 Prometheus 指标
- 多租户 namespace 隔离（数据、API、执行、Skills 可见性）
- Docker 容器级执行沙箱（默认）/ 本地目录隔离（开发模式）
- Skill 可执行化（entrypoint 通过 ExecutionBackend 沙箱执行）
- RPC/HTTP 全接口 namespace 强制化
- 28 个验收测试（跨租户拒绝、路径穿越防护、skill 可见性）

## License

MIT
