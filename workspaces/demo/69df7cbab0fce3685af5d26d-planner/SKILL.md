---
entrypoint: "./run.sh"
args_schema: '{"type":"object","properties":{"prompt":{"type":"string","description":"Current user prompt"},"history":{"type":"array","items":{"type":"object"},"description":"Conversation history"},"mode":{"type":"string","enum":["plan","route","execute"],"default":"execute"},"knowledge_api_url":{"type":"string"},"data_api_url":{"type":"string"},"headers":{"type":"object","additionalProperties":{"type":"string"}},"request_body":{"type":"object"}},"required":["prompt"]}'
---
# Planning Query Router
Analyze prompt plus context, build a plan, and automatically route to either knowledge QA API or data QA API with SSE streaming.

## Input format
Pass JSON string when possible:

```json
{
  "prompt": "How many paid users did we get last week?",
  "history": [
    {"role": "user", "content": "Use BI data"},
    {"role": "assistant", "content": "Sure, share the metric scope"}
  ],
  "mode": "route",
  "knowledge_api_url": "http://knowledge-qa/sse",
  "data_api_url": "http://data-qa/sse"
}
```

If input is plain text, it is treated as `prompt`.

## StreamChatController usage
- Plain text input is enough. The skill auto-runs with `mode=execute`.
- Optional JSON input can override mode/history/headers/body.
- API endpoints can be provided in input, or via env vars:
  - `KNOWLEDGE_QA_API_URL` (or `KNOWLEDGE_SSE_API_URL` / `KNOWLEDGE_API_URL`)
  - `DATA_QA_API_URL` (or `DATA_SSE_API_URL` / `DATA_API_URL`)

## Modes
- default `execute`: auto route and call selected SSE API (self-contained flow for StreamChatController).
- `plan`: output only task steps.
- `route`: output route decision, confidence, and plan.
- `execute`: choose the API and consume SSE stream from the chosen endpoint.

## Route policy
- Route to `data` when prompt/history emphasizes metrics, SQL, tables, trends, aggregations, filters, or time windows.
- Route to `knowledge` when prompt/history emphasizes concepts, definitions, principles, explanations, or how-to guidance.
- Include matched signals and confidence in output for traceability.

## SSE execution
- Script sends `POST` request to the selected API with `Accept: text/event-stream`.
- Script prints SSE `data:` chunks as they arrive.
- If selected route URL is missing but the other URL exists, script auto-fallbacks to the other API.
- If both URLs are missing, script returns a structured no-endpoint result.
