# Delphi Agent Framework

[English](./README.md)

> 一个面向平台化使用场景、基于 PI-Agent 思路实现的 Agent 框架。

## 概览

`delphi-agent` 是一个多模块 Java 框架，用于构建平台级 Agent 后端，提供：

- 基于 turn loop 的 Agent 执行
- SSE 流式输出
- MongoDB 会话持久化
- 多租户 namespace 隔离
- 可插拔模型/Provider 体系
- Skill 目录加载与沙箱执行

本仓库既包含可复用组件，也包含可直接运行的参考服务 `delphi-agent-server`。

## 架构与深度走查

- [全面深度走查（2026-04-21）](./ARCHITECTURE.zh-CN.md)
- [文档导航](./DOCS.md)

## 核心能力

- 通过 `delphi-agent-ai-api` 统一模型调度（`AiRuntime`、`ApiProviderRegistry`、`ModelCatalog`）
- Agent 运行时支持工具调用（并行/串行）
- 会话能力：create、prompt、continue、steer、follow-up、fork、tree navigate、compact
- 提供流式聊天、资源目录、审计、用量查询等 HTTP API
- Session/RPC 处理能力在 runtime/sdk 层可用
- Skill 可见性按 namespace 分层：`public` + `namespaces/<tenant>`
- 两种执行后端：
  - Docker 隔离后端（默认）
  - 本地隔离后端（`local-dev`）

## 模块结构

| 模块 | 职责 |
| --- | --- |
| `delphi-agent-ai-api` | 统一 AI 协议、Provider SPI 与运行时调度 |
| `delphi-agent-springai-provider` | Spring AI 适配实现 |
| `delphi-agent-core` | Agent turn loop 与工具编排核心 |
| `delphi-agent-runtime` | 会话运行时、持久化、沙箱执行、资源目录、SDK、RPC 处理 |
| `delphi-agent-http-api` | REST / SSE 控制器层 |
| `delphi-agent-server` | 可运行的 Spring Boot 服务（含自动配置） |

## 参考服务内置模型

参考服务在 `AiProviderConfiguration` 中注册了以下模型：

| Provider | Model IDs |
| --- | --- |
| `deepseek` | `deepseek-chat`, `deepseek-reasoner` |
| `zhipuai` | `glm-4`, `glm-4-flash` |

说明：

- 虽然配置层支持 `OPENAI_API_KEY` 桥接，但当前参考服务默认未在模型目录中注册 OpenAI 模型。
- 如需扩展，可新增 `ApiProvider` Bean 与 `ModelCatalog` 注册项。

## 快速开始

### 环境要求

- JDK 21
- Maven 3.9+
- MongoDB 5+
- 至少一个模型 Provider Key（`DEEPSEEK_API_KEY` 或 `ZHIPUAI_API_KEY`）
- Docker（仅默认沙箱模式需要）

### 1. 构建

```bash
mvn -q -DskipTests package
```

### 2. 配置环境变量

```bash
cp .env.example .env
```

示例 `.env`：

```properties
PORT=8080
MONGODB_URI=mongodb://localhost:27017/pi-agent-framework

DEEPSEEK_API_KEY=sk-your-deepseek-key
ZHIPUAI_API_KEY=your-zhipuai-key
# OPENAI_API_KEY=sk-your-openai-key

PI_AGENT_SKILLS_DIRS=./skills,$HOME/.codex/skills
PI_AGENT_PROMPTS_DIRS=./prompts
PI_AGENT_RESOURCES_DIRS=./resources
```

### 3. 启动服务

```bash
set -a && source .env && set +a

# 默认：Docker 隔离执行后端
mvn -q -pl delphi-agent-server spring-boot:run

# 开发模式：本地后端（无需 Docker）
mvn -q -pl delphi-agent-server spring-boot:run -Dspring-boot.run.profiles=local-dev
```

默认地址：`http://localhost:8080`

### 4. 验证

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/catalog/models
```

## 主要调用流程

> 说明：下文 Session/RPC 章节描述的是 runtime 能力与历史协议契约。  
> 当前参考服务对外主要暴露 `/api/chat/**`、`/api/catalog/**`、`/api/audit`、`/api/usage`。

### 1）一次性流式聊天（不持久化会话）

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "demo",
    "prompt": "用 Java 写一个快速排序",
    "provider": "deepseek",
    "modelId": "deepseek-chat",
    "systemPrompt": "你是资深 Java 工程师"
  }'
```

SSE 事件包括：`agent_start`、`turn_start`、`message_start`、`text`、`tool_start`、`tool_end`、`turn_end`、`done`、`error`。

### 2）Session 模式

创建会话：

```bash
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "demo",
    "projectKey": "backend",
    "sessionName": "review-1",
    "provider": "deepseek",
    "modelId": "deepseek-chat"
  }' | jq -r '.sessionId')
```

发送 prompt（异步 accepted）：

```bash
curl -X POST "http://localhost:8080/api/sessions/$SESSION_ID/prompt?namespace=demo" \
  -H "Content-Type: application/json" \
  -d '{"message":"请给出这份设计的优化建议。"}'
```

订阅事件：

```bash
curl -N "http://localhost:8080/api/sessions/$SESSION_ID/events?namespace=demo"
```

查看状态：

```bash
curl "http://localhost:8080/api/sessions/$SESSION_ID/state?namespace=demo"
```

### 3）RPC 命令模式

```bash
curl -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "cmd-1",
    "type": "get_state",
    "sessionId": "'"$SESSION_ID"'",
    "namespace": "demo"
  }'
```

## HTTP API 一览

### Session API（`/api/sessions`）

- `POST /api/sessions`
- `GET /api/sessions?namespace=<ns>&projectKey=<project>`
- `GET /api/sessions/models?provider=<optional>`
- `GET /api/sessions/{sessionId}/state?namespace=<ns>`
- `GET /api/sessions/{sessionId}/messages?namespace=<ns>`
- `GET /api/sessions/{sessionId}/tree?namespace=<ns>`
- `POST /api/sessions/{sessionId}/prompt?namespace=<ns>`
- `POST /api/sessions/{sessionId}/continue?namespace=<ns>`
- `POST /api/sessions/{sessionId}/steer?namespace=<ns>`
- `POST /api/sessions/{sessionId}/follow-up?namespace=<ns>`
- `POST /api/sessions/{sessionId}/compact?namespace=<ns>`
- `POST /api/sessions/{sessionId}/abort?namespace=<ns>`
- `POST /api/sessions/{sessionId}/model?namespace=<ns>`
- `POST /api/sessions/{sessionId}/thinking-level?namespace=<ns>`
- `POST /api/sessions/{sessionId}/fork?namespace=<ns>`
- `POST /api/sessions/{sessionId}/navigate?namespace=<ns>`
- `GET /api/sessions/{sessionId}/events?namespace=<ns>`（SSE）

### RPC API（`/api/rpc`）

- `POST /api/rpc/command`
- `GET /api/rpc/events/{sessionId}?namespace=<ns>`（SSE）
- `GET /api/rpc/catalog/skills?namespace=<ns>`
- `GET /api/rpc/catalog/prompts`
- `GET /api/rpc/catalog/resources`
- `POST /api/rpc/catalog/reload`

### Skills API（`/api/skills`）

- `GET /api/skills?namespace=<ns>`
- `GET /api/skills/{name}?namespace=<ns>`
- `POST /api/skills/reload?namespace=<optional>`

## 已实现 RPC 命令

`prompt`、`steer`、`follow_up`、`abort`、`new_session`、`get_state`、`set_model`、`cycle_model`、`get_available_models`、`set_thinking_level`、`cycle_thinking_level`、`set_steering_mode`、`set_follow_up_mode`、`compact`、`set_auto_compaction`、`set_auto_retry`、`abort_retry`、`bash`、`abort_bash`、`get_session_stats`、`export_html`、`switch_session`、`fork`、`get_fork_messages`、`get_last_assistant_text`、`set_session_name`、`get_messages`、`get_commands`

## Skills / Prompts / Resources 目录

资源目录配置：

- `PI_AGENT_SKILLS_DIRS`（默认 `./skills,$HOME/.codex/skills`）
- `PI_AGENT_PROMPTS_DIRS`（默认 `./prompts`）
- `PI_AGENT_RESOURCES_DIRS`（默认 `./resources`）

Skill 可见性规则：

- `skills/public/**`：所有 namespace 可见
- `skills/namespaces/<namespace>/**`：仅该 namespace 可见
- namespace 内同名 skill 会覆盖 public 同名 skill

可执行 Skill frontmatter 示例：

```markdown
---
entrypoint: "./deploy.sh"
args_schema: '{"type":"object","properties":{"env":{"type":"string"}}}'
---
# Deploy
Deploys the service.
```

## 隔离与安全模型

- 会话相关操作要求请求 namespace 与会话所属 namespace 一致
- 对 namespace / session 路径段执行路径穿越校验
- 工作目录隔离路径：`workspaces/<namespace>/<sessionId>`
- 默认后端使用 Docker，启用：
  - `--network none`
  - 只读根文件系统 + 独立 workspace 挂载
  - 内存 / CPU / 进程数限制
- `local-dev` 后端仅提供目录级隔离，不提供容器级隔离

详细说明见 [SANDBOX.md](./SANDBOX.md)。

## 扩展方式

### 自定义工具

实现 `AgentTool`，注册到 `agent.state().tools(...)`。

### 生命周期扩展

实现 `AgentExtension` 可接入：

- `beforeNewSession`
- `beforeFork`
- `beforeNavigateTree`
- `beforeCompact`
- `onAgentEvent`
- `slashCommands`

扩展作为 Spring Bean 自动发现。

## 示例脚本

参考 [samples/README.md](./samples/README.md)：

- 基础 Skill 使用
- 沙箱隔离
- 多 namespace 可见性与访问控制
- 同 namespace 多用户场景
- 综合端到端示例

## 开发

运行全部测试：

```bash
mvn test
```

仅运行某模块测试：

```bash
mvn -pl delphi-agent-runtime test
```
