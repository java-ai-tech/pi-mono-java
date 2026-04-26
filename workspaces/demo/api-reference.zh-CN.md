# Delphi Agent API 使用手册

> 本文档为 `delphi-agent-server` 对外 HTTP/SSE 接口的使用手册，覆盖请求结构、SSE 事件、命令、错误模型与示例。

## 1. 概览

| 项 | 值 |
| --- | --- |
| Base URL | `http://<host>:<port>` |
| 默认端口 | `8080`（`PORT` 环境变量可覆盖） |
| 内容类型 | 请求 `application/json`；SSE 响应 `text/event-stream` |
| 字符集 | UTF-8 |
| 路径前缀 | `/api` |

### 1.1 请求头

| Header | 是否必须 | 说明 |
| --- | --- | --- |
| `Content-Type: application/json` | 是 | 所有 POST 请求均需 JSON |
| `X-Tenant-Id` | **是** | 租户标识，**必须等于请求体的 `namespace`** |
| `X-User-Id` | **是** | 当前用户标识，用于审计 / 计量 |

### 1.2 多租户语义

- **`X-Tenant-Id` 必须等于请求体的 `namespace`**，否则返回 `{"error":"internal_error","message":"namespace is not authorized for tenant"}`。
- 服务端按 `namespace` 强校验 session 归属，跨 namespace 访问会被拒绝。
- 所有审计、计量按 `(tenantId, namespace)` 聚合。
- 当前实现中 `tenantId` 与 `namespace` 是 1:1 映射关系。

---

## 2. POST `/api/chat/stream`

主入口：发送 prompt、执行会话命令，统一返回 SSE 流。

### 2.1 请求体

```json
{
  "namespace": "demo",
  "prompt": "帮我用 Python 写一个 quicksort",
  "sessionId": null,
  "command": null,
  "queueMode": "interrupt",
  "provider": "deepseek",
  "modelId": "deepseek-v4-pro",
  "systemPrompt": null,
  "projectKey": "proj-123",
  "commandArgs": null
}
```

| 字段 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `namespace` | string | ✅ | - | 多租户隔离 namespace。`@NotBlank` |
| `prompt` | string | ⚠️ | - | 用户输入。`command` 为 null 时必填；`command=steer` 时复用此字段 |
| `sessionId` | string | - | 自动创建 | 留空会自动创建一个新 session 并通过 SSE 返回 |
| `command` | string | - | `null` | 见 §2.4，`null` 表示普通 prompt |
| `queueMode` | string | - | `interrupt` | 队列策略，见 §2.3 |
| `provider` | string | - | 注册的第一个 | 模型 provider，例如 `deepseek` |
| `modelId` | string | - | 注册的第一个 | 模型 ID，例如 `deepseek-v4-pro` |
| `systemPrompt` | string | - | - | 仅在创建新 session 时生效，作为该 session 的系统提示 |
| `projectKey` | string | - | - | 项目级标识，subagent 工作区作用域 = `project` 时生效 |
| `commandArgs` | object | - | - | command 的参数，见 §2.4 |

### 2.2 响应

- 状态码 `200 OK`
- `Content-Type: text/event-stream`
- 流式 SSE 帧，事件清单见 §3

异常情况：
- `400 Bad Request`：请求体校验失败（如 namespace 为空）
- `403 Forbidden`：跨 namespace 访问、配额拒绝（也可能在流内通过 `quota_rejected` 事件返回）
- `429 Too Many Requests`：限流命中
- `500 Internal Server Error`：未分类异常

### 2.3 `queueMode` 队列策略

控制同一 session 上有 run 在跑时新请求的处理方式。

| 取值 | 含义 |
| --- | --- |
| `interrupt`（默认） | 中止当前 run，立即开始新 run |
| `followup` | 在当前 run 之后排队执行（FIFO） |
| `steer` | 把新 prompt 注入到正在进行的 run（语义级 steer） |
| `drop` | 当前有 run 时静默丢弃新请求 |
| `reject` | 当前有 run 时直接拒绝并返回 `quota_rejected` 事件 |

> 大小写不敏感；非法值降级为 `interrupt`。

### 2.4 `command` 会话级命令

`command != null` 时不会触发 LLM 推理，返回单一的 `ack` 事件。

| `command` | `commandArgs` 字段 | 行为 |
| --- | --- | --- |
| `steer` | - | `prompt` 作为 steer 文本注入到当前 run |
| `abort` | - | 中止当前 run |
| `compact` | `keep` (int) | 压缩历史，保留最近 N 条 |
| `continue` | - | 触发 continue 语义 |
| `fork` | `entryId` | 从指定 entry 分叉出新 session，返回 `forkSessionId` |
| `navigate` | `entryId` | 切换到历史分支 |
| `set_model` | `provider`, `modelId` | 修改 session 当前模型 |
| `set_thinking` | `level` (`OFF`/`LOW`/`HIGH`) | 设置思考强度 |

`ack` 事件 payload 示例：
```json
{ "command": "fork", "forkSessionId": "ses_abcd1234" }
```

### 2.5 curl 示例

普通 prompt：
```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: demo" \
  -H "X-User-Id: user-1" \
  -d '{
    "namespace": "demo",
    "prompt": "解释一下 raft 算法",
    "queueMode": "interrupt",
    "provider": "deepseek",
    "modelId": "deepseek-v4-pro"
  }'
```

**注意**：`X-Tenant-Id` 必须等于 `namespace`（本例都是 `demo`）。

中止当前 run：
```bash
curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{ "namespace":"demo", "sessionId":"ses_xxx", "command":"abort" }'
```

分叉 session：
```bash
curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "namespace":"demo", "sessionId":"ses_xxx", "command":"fork",
    "commandArgs": { "entryId": "ent_123" }
  }'
```

---

## 3. SSE 事件清单

每个 SSE 帧形如：
```
event: <name>
data: { "eventId":"evt_...", "name":"...", "runId":"...", "tenantId":"...",
        "namespace":"...", "userId":"...", "sessionId":"...", "subagentId":null,
        "timestamp":"2026-04-26T08:00:00Z", "data": { ... } }
```

`data` 字段是事件 payload，下表列出每个事件的关键字段。

### 3.1 Run 生命周期

| 事件 | 何时触发 | 关键 payload |
| --- | --- | --- |
| `run_started` | run 开始 | `runId`, `tenantId`, `sessionId` |
| `run_completed` | run 正常结束 | `runId`, `status=COMPLETED`, `finalText` |
| `run_failed` | run 失败 | `runId`, `status=FAILED`, `failureType`, `message` |
| `quota_rejected` | 配额或队列拒绝 | `runId`, `reason` |
| `queue_updated` | 入队/丢弃/steer 决策 | `runId`, `decision`, `queueSize?`, `dropped?`, `steered?` |

收到 `run_completed` / `run_failed` / `quota_rejected` 之后，server 会主动 `complete()` SSE 流。

### 3.2 消息流

| 事件 | 关键 payload |
| --- | --- |
| `message_start` | `role`（一般为 `assistant`） |
| `message_delta` | `delta`（增量文本片段） |
| `message_end` | `role` |

### 3.3 工具调用

| 事件 | 关键 payload |
| --- | --- |
| `tool_started` | `toolName`, `toolCallId` |
| `tool_updated` | `toolName`, `toolCallId`, `partial` |
| `tool_completed` | `toolName`, `toolCallId`, `result`, `isError` |

### 3.4 Subagent

主代理调用 `subagent_*` 编排工具时会派发以下事件，所有 subagent 事件 payload 都带 `subagentId`：

| 事件 | 关键 payload |
| --- | --- |
| `subagent_started` | `subagentId`, `role`, `task`, `workspaceScope` |
| `subagent_message_delta` | `subagentId`, `delta` |
| `subagent_tool_started` | `subagentId`, `toolName`, `toolCallId` |
| `subagent_tool_completed` | `subagentId`, `toolName`, `result`, `isError` |
| `subagent_completed` | `subagentId`, `result` |
| `subagent_failed` | `subagentId`, `failureType`, `message` |

### 3.5 命令应答

| 事件 | 关键 payload |
| --- | --- |
| `ack` | `command`, 以及 command 自身的返回字段（如 `forkSessionId`） |

---

## 4. 其它接口

### 4.1 GET `/api/catalog`

返回当前 namespace 可见的 skills / prompts / resources 目录。

| 查询参数 | 说明 |
| --- | --- |
| `namespace` | 必填 |

### 4.2 GET `/api/audit`

按 `namespace` + 时间窗查询审计日志，分页返回。

### 4.3 GET `/api/usage`

返回 `(namespace, date)` 维度的 token / 调用计量。

---

## 5. 错误与失败类型

`run_failed` 事件中的 `failureType` 取自 `RunFailureType` 枚举：

| failureType | 含义 |
| --- | --- |
| `QUOTA_REJECTED` | 配额拦截 |
| `TENANT_GUARD_REJECTED` | 租户守卫拒绝（如 namespace 越权） |
| `MODEL_PROVIDER_ERROR` | 上游模型 provider 报错 |
| `TOOL_EXECUTION_ERROR` | 工具执行失败 |
| `SANDBOX_ERROR` | 沙箱执行失败（Docker / Local） |
| `INTERNAL_ERROR` | 未分类内部错误 |

> 实际枚举以 `delphi-agent-runtime` 中 `RunFailureType` 为准。

---

## 6. 端到端典型时序

```
Client                                Server
  │  POST /api/chat/stream             │
  │ ────────────────────────────────▶  │
  │                                    │ run_started
  │ ◀───────────────────────────────── │
  │                                    │ message_start
  │ ◀───────────────────────────────── │
  │                                    │ message_delta × N
  │ ◀───────────────────────────────── │
  │                                    │ tool_started
  │ ◀───────────────────────────────── │
  │                                    │ tool_completed
  │ ◀───────────────────────────────── │
  │                                    │ message_delta × N
  │ ◀───────────────────────────────── │
  │                                    │ message_end
  │ ◀───────────────────────────────── │
  │                                    │ run_completed (close)
  │ ◀───────────────────────────────── │
```

---

## 7. 客户端实现要点

1. SSE 解析时按 `\n\n` 切帧，按 `data:` 提取 JSON。
2. 对 `data.name` 做 switch，重点处理 `message_delta`、`tool_*`、`subagent_*`、`run_completed`、`run_failed`。
3. `runId` 和 `sessionId` 都需要持有：`runId` 用于打印日志和定位审计，`sessionId` 用于继续会话。
4. 需要中止时再次发送 `command=abort` 即可，不要直接断 SSE 连接（断连不保证 server 立即停止）。
5. `queueMode=steer` 时,新 prompt 不会得到独立 run 流，会通过当前 run 的 `message_delta` 反映出来。

---

## 8. 工具策略与权限

### 8.1 工具类目

| 类目 | 包含工具 | 说明 |
| --- | --- | --- |
| `READONLY` | `read`, `grep`, `find`, `ls` | 只读文件系统 |
| `MUTATING` | `write`, `edit` | 修改文件 |
| `EXECUTABLE` | `bash` | 执行 shell 命令 |
| `INSTRUCTIONAL` | 大部分 skills | 指导性工具（不直接操作文件系统） |
| `ORCHESTRATION` | `subagent_spawn`, `subagent_status`, `subagent_result`, `subagent_abort` | 子代理编排 |

### 8.2 角色权限矩阵

| 角色 | READONLY | INSTRUCTIONAL | ORCHESTRATION | MUTATING | EXECUTABLE |
| --- | --- | --- | --- | --- | --- |
| **ORCHESTRATOR**（主代理） | ✅ | ✅ | ✅ | ✅ (默认) | ✅ (默认) |
| PLANNER / RESEARCHER | ✅ | ✅ | ❌ | ❌ | ❌ |
| REVIEWER | ✅ | ❌ | ❌ | ❌ | ❌ |
| CODER | ✅ | ✅ | ❌ | ✅ | ✅ |
| TESTER | ✅ | ✅ | ❌ | ❌ | ✅ |

**注意**：
- ORCHESTRATOR 默认拥有全部类目权限（`pi.tool-policy.orchestrator-strict=false`）
- 如需启用严格模式（主代理只编排，危险操作下发 subagent），设置 `pi.tool-policy.orchestrator-strict=true`
- 工具被策略拒绝时，`tool_completed` 事件的 `result` 会包含 `"denied by runtime policy: <reason>"`

### 8.3 配置项

在 `application.yml` 中：

```yaml
pi:
  tool-policy:
    orchestrator-strict: false  # true 启用严格模式
```
