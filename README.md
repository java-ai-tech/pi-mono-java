# Delphi Agent Framework

[中文文档](./README.zh-CN.md)

> An Agent Framework for Platform Use, implemented based on PI-Agent.

## Overview

`delphi-agent` is a multi-module Java framework for building platform-grade agent backends.  
It provides:

- A turn-based Agent loop with tool calling
- Streaming output over SSE
- Session persistence with MongoDB
- Multi-tenant namespace isolation
- Pluggable model/provider architecture
- Skill catalog + sandboxed skill execution

This repository contains both reusable libraries and a runnable reference server (`delphi-agent-server`).

## Key Capabilities

- Multi-provider model runtime via `delphi-agent-ai-api` (`AiRuntime`, `ApiProviderRegistry`, `ModelCatalog`)
- Agent execution core with parallel/sequential tool execution support
- Session features: create, prompt, continue, steer, follow-up, fork, tree navigation, compact
- HTTP APIs for Session, RPC, Skill catalog, and one-shot streaming chat
- Namespace-aware skill visibility: `public` + `namespaces/<tenant>`
- Two execution backends:
  - Docker isolated backend (default profile)
  - Local isolated backend (`local-dev` profile)

## Project Structure

| Module | Responsibility |
| --- | --- |
| `delphi-agent-ai-api` | Unified AI contracts, provider SPI, and runtime dispatch |
| `delphi-agent-springai-provider` | Spring AI adapter implementation |
| `delphi-agent-core` | Core turn-loop agent runtime and tool orchestration |
| `delphi-agent-runtime` | Session runtime, persistence, sandbox execution, resource catalog, SDK, RPC processor |
| `delphi-agent-http-api` | REST/SSE controllers |
| `delphi-agent-server` | Runnable Spring Boot server with auto-configuration |

## Reference Server: Built-in Providers and Models

The reference server registers the following models in `AiProviderConfiguration`:

| Provider | Model IDs |
| --- | --- |
| `deepseek` | `deepseek-chat`, `deepseek-reasoner` |
| `zhipuai` | `glm-4`, `glm-4-flash` |

Notes:

- `OPENAI_API_KEY` is bridged by config, but OpenAI models are not registered in the current reference server model catalog by default.
- You can add more providers/models by registering extra `ApiProvider` beans and `ModelCatalog` entries.

## Quick Start

### Prerequisites

- JDK 21
- Maven 3.9+
- MongoDB 5+
- At least one model provider key (`DEEPSEEK_API_KEY` or `ZHIPUAI_API_KEY`)
- Docker (only required for default sandbox profile)

### 1. Build

```bash
mvn -q -DskipTests package
```

### 2. Configure environment

```bash
cp .env.example .env
```

Example `.env`:

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

### 3. Start server

```bash
set -a && source .env && set +a

# Docker-isolated backend (default)
mvn -q -pl delphi-agent-server spring-boot:run

# Local backend (development only, no Docker required)
mvn -q -pl delphi-agent-server spring-boot:run -Dspring-boot.run.profiles=local-dev
```

Server default: `http://localhost:8080`

### 4. Verify

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/sessions/models
```

## Core API Flows

### 1) One-shot streaming chat (no persisted session)

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "demo",
    "prompt": "Write a quicksort in Java",
    "provider": "deepseek",
    "modelId": "deepseek-chat",
    "systemPrompt": "You are a senior Java engineer"
  }'
```

SSE events include: `agent_start`, `turn_start`, `message_start`, `text`, `tool_start`, `tool_end`, `turn_end`, `done`, `error`.

### 2) Session mode

Create session:

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

Send prompt (async accepted):

```bash
curl -X POST "http://localhost:8080/api/sessions/$SESSION_ID/prompt?namespace=demo" \
  -H "Content-Type: application/json" \
  -d '{"message":"Review this design and suggest improvements."}'
```

Subscribe events:

```bash
curl -N "http://localhost:8080/api/sessions/$SESSION_ID/events?namespace=demo"
```

Read state:

```bash
curl "http://localhost:8080/api/sessions/$SESSION_ID/state?namespace=demo"
```

### 3) RPC command mode

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

## HTTP API Surface

### Session APIs (`/api/sessions`)

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
- `GET /api/sessions/{sessionId}/events?namespace=<ns>` (SSE)

### RPC APIs (`/api/rpc`)

- `POST /api/rpc/command`
- `GET /api/rpc/events/{sessionId}?namespace=<ns>` (SSE)
- `GET /api/rpc/catalog/skills?namespace=<ns>`
- `GET /api/rpc/catalog/prompts`
- `GET /api/rpc/catalog/resources`
- `POST /api/rpc/catalog/reload`

### Skills APIs (`/api/skills`)

- `GET /api/skills?namespace=<ns>`
- `GET /api/skills/{name}?namespace=<ns>`
- `POST /api/skills/reload?namespace=<optional>`

## RPC Commands (Implemented)

`prompt`, `steer`, `follow_up`, `abort`, `new_session`, `get_state`, `set_model`, `cycle_model`, `get_available_models`, `set_thinking_level`, `cycle_thinking_level`, `set_steering_mode`, `set_follow_up_mode`, `compact`, `set_auto_compaction`, `set_auto_retry`, `abort_retry`, `bash`, `abort_bash`, `get_session_stats`, `export_html`, `switch_session`, `fork`, `get_fork_messages`, `get_last_assistant_text`, `set_session_name`, `get_messages`, `get_commands`

## Skills, Prompts, and Resources

Configured catalog roots:

- `PI_AGENT_SKILLS_DIRS` (default `./skills,$HOME/.codex/skills`)
- `PI_AGENT_PROMPTS_DIRS` (default `./prompts`)
- `PI_AGENT_RESOURCES_DIRS` (default `./resources`)

Skill visibility model:

- `skills/public/**` => visible to all namespaces
- `skills/namespaces/<namespace>/**` => visible only to that namespace
- Same-name namespace skill overrides same-name public skill

Executable skill frontmatter example:

```markdown
---
entrypoint: "./deploy.sh"
args_schema: '{"type":"object","properties":{"env":{"type":"string"}}}'
---
# Deploy
Deploys the service.
```

## Isolation and Security Model

- Namespace must match the session owner namespace for session-bound operations
- Path traversal checks are applied to namespace/session path segments
- Workspace isolation path pattern: `workspaces/<namespace>/<sessionId>`
- Default backend uses Docker with:
  - `--network none`
  - read-only rootfs + isolated workspace mount
  - memory / CPU / PIDs limits
- `local-dev` backend keeps path-level isolation but is not container-isolated

Details: see [SANDBOX.md](./SANDBOX.md).

## Extensibility

### Custom Tool

Implement `AgentTool` and register on `agent.state().tools(...)`.

### Lifecycle Extension

Implement `AgentExtension` to hook:

- `beforeNewSession`
- `beforeFork`
- `beforeNavigateTree`
- `beforeCompact`
- `onAgentEvent`
- `slashCommands`

Extensions are auto-discovered as Spring beans.

## Sample Scenarios

See [samples/README.md](./samples/README.md) for end-to-end scripts:

- Basic skill usage
- Sandbox isolation
- Multi-namespace visibility and access control
- Multi-user same-namespace scenario
- Comprehensive demo

## Development

Run tests:

```bash
mvn test
```

Run a specific module:

```bash
mvn -pl delphi-agent-runtime test
```
