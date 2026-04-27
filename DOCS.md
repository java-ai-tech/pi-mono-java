# Delphi Agent API Docs

`DOCS.md` 只作为 API 文档入口。技术架构图请看 [ARCHITECHE.MD](./ARCHITECHE.MD)，项目使用说明请看 [README.md](./README.md)。

## API Reference

- [API Reference (中文)](./docs/api-reference.zh-CN.md)

## API Surface

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/chat/stream` | 主入口：发送 prompt、执行 command，统一返回 SSE。 |
| `GET` | `/api/catalog/models?provider=<provider>` | 查询模型列表。 |
| `GET` | `/api/catalog/skills?namespace=<ns>` | 查询 namespace 可见 skills。 |
| `GET` | `/api/catalog/skills/{name}?namespace=<ns>` | 查询单个 skill。 |
| `GET` | `/api/catalog/prompts` | 查询 prompt catalog。 |
| `GET` | `/api/catalog/resources` | 查询 resource catalog。 |
| `POST` | `/api/catalog/reload?namespace=<ns>` | 重扫 catalog，并清理 skill cache。 |
| `GET` | `/api/audit?namespace=<ns>&from=&to=&limit=` | 查询审计日志。 |
| `GET` | `/api/usage?namespace=<ns>&from=&to=` | 查询用量统计。 |
| `GET` | `/api/usage/today?namespace=<ns>` | 查询今日用量。 |

## Required Headers

| Header | 说明 |
| --- | --- |
| `Content-Type: application/json` | POST 请求必须使用 JSON。 |
| `X-Tenant-Id` | 租户标识，必须等于请求体中的 `namespace`。 |
| `X-User-Id` | 当前用户标识，用于审计和计量。 |

## Main SSE Events

| 类别 | 事件 |
| --- | --- |
| Run | `run_started`、`queue_updated`、`run_completed`、`run_failed`、`quota_rejected` |
| Message | `message_start`、`message_delta`、`message_end` |
| Tool | `tool_started`、`tool_updated`、`tool_completed` |
| Subagent | `subagent_started`、`subagent_completed`、`subagent_failed` |
| Command | `ack` |
