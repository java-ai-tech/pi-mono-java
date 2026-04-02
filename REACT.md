# ReAct (Reasoning and Acting) 实现原理

本文档详细说明 delphi-agent 中 ReAct 模式的实现原理。ReAct 是一种让 LLM 交替进行「推理」(Reasoning) 和「行动」(Acting) 的架构模式，框架通过 turn-based agent loop 实现了这一范式。

## 核心思想

传统 LLM 调用是单轮的：用户输入 → 模型输出。ReAct 模式扩展为多轮循环：

```
用户输入 → 模型推理 → 决定调用工具(Act) → 获取工具结果 → 继续推理 → ... → 最终回答
```

模型在每一轮中同时扮演「思考者」和「决策者」：
- **Reasoning** — 模型分析当前上下文，决定下一步需要做什么
- **Acting** — 模型通过 tool calling 执行具体操作（执行代码、查询数据、调用 API 等）

循环持续到模型认为任务完成（不再调用工具），才输出最终回答。

## 架构总览

```
                    ┌─────────────────────────────────────────┐
                    │              Agent.runLoop()            │
                    │         (ReAct 核心循环引擎)              │
                    └────────────────┬────────────────────────┘
                                     │
          ┌──────────────────────────┼──────────────────────────┐
          │                          │                          │
    ┌─────▼─────┐            ┌──────▼──────┐           ┌──────▼──────┐
    │ AgentState │            │  AiRuntime   │           │  AgentTool  │
    │            │            │ (LLM 调用)   │           │ (工具执行)   │
    │ - messages │            │              │           │             │
    │ - tools    │◄──────────►│ streamSimple │           │ execute()   │
    │ - model    │            │              │           │             │
    └───────────┘            └─────────────┘           └─────────────┘
```

关键类：

| 类 | 文件 | 职责 |
|---|---|---|
| `Agent` | `delphi-agent-core/.../Agent.java` | ReAct 循环引擎，编排 LLM 调用和 Tool 执行 |
| `AgentState` | `delphi-agent-core/.../AgentState.java` | 持有对话历史、注册工具、当前模型等运行时状态 |
| `AgentTool` | `delphi-agent-core/.../AgentTool.java` | 工具接口，每个工具提供 name/description/schema/execute |
| `AgentEvent` | `delphi-agent-core/.../AgentEvent.java` | 密封接口(sealed interface)，定义循环中所有可观测事件 |
| `AgentOptions` | `delphi-agent-core/.../AgentOptions.java` | 配置项：工具执行模式、生命周期钩子、上下文变换 |

## Turn Loop 详细流程

`Agent.runLoop()` 是 ReAct 的核心实现（`Agent.java:119-218`）。完整流程如下：

### 阶段 1：初始化

```java
state.streaming(true);
state.error(null);
emit(new AgentEvent.AgentStart());
emit(new AgentEvent.TurnStart());

// 将用户消息加入上下文
for (AgentMessage prompt : prompts) {
    emit(new AgentEvent.MessageStart(prompt));
    emit(new AgentEvent.MessageEnd(prompt));
    contextMessages.add(prompt);
    state.appendMessage(prompt);
}
```

Agent 进入 streaming 状态，发出 `AgentStart` 和 `TurnStart` 事件，然后将用户输入追加到对话上下文。

### 阶段 2：Reasoning — LLM 流式响应

```java
AgentAssistantMessage assistant = streamAssistantResponse(contextMessages);
```

`streamAssistantResponse()` 完成以下工作：

1. **上下文变换** — 通过 `options.transformContext()` 预处理消息（可用于 token 裁剪）
2. **消息格式转换** — `AgentMessage → LLM Message`（通过 `options.convertToLlm()`）
3. **构建 LLM 请求** — system prompt + 对话历史 + 工具定义（从注册的 AgentTool 转换为 JSON Schema）

```java
Context llmContext = new Context(state.systemPrompt(), llmMessages,
    state.tools().stream()
        .map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.parametersSchema()))
        .toList());
```

4. **流式调用 LLM** — 通过 `aiRuntime.streamSimple()` 发起 SSE 流式请求
5. **实时事件推送** — 订阅 `Flow.Publisher`，每个 delta token 触发 `MessageUpdate` 事件

LLM 会在响应中同时返回文本推理内容和 tool call 指令（如果需要行动）。

### 阶段 3：Acting — 检测并执行工具调用

LLM 响应完成后，框架从 assistant message 中提取 tool call 指令：

```java
List<ToolCallContent> toolCalls = assistant.content().stream()
    .filter(ToolCallContent.class::isInstance)
    .map(ToolCallContent.class::cast)
    .toList();

hasMoreToolCalls = !toolCalls.isEmpty();
```

如果存在 tool calls，执行工具并将结果注入上下文：

```java
if (hasMoreToolCalls) {
    toolResults = executeToolCalls(contextMessages, assistant, toolCalls);
    for (AgentToolResultMessage result : toolResults) {
        contextMessages.add(result);
        state.appendMessage(result);
    }
}
```

### 阶段 4：循环判定 — ReAct 的关键

这是 ReAct 模式的核心决策点。框架使用双层循环结构：

```java
while (true) {
    // 内层循环：有 tool call 或有 steering 消息就继续 Reason-Act
    while (hasMoreToolCalls || !pending.isEmpty()) {
        // 1. 注入 steering/pending 消息
        // 2. 调用 LLM (Reasoning)
        // 3. 提取 tool calls
        // 4. 执行工具 (Acting)
        // 5. 检查 steering 队列
        pending = getSteeringMessages();
    }

    // 外层循环：检查 follow-up 队列
    List<AgentMessage> followUps = getFollowUpMessages();
    if (followUps.isEmpty()) {
        break;  // 没有后续任务，ReAct 循环结束
    }
    pending = followUps;  // 有 follow-up，开始新一轮 ReAct
}
```

**终止条件**（全部满足才终止）：
1. LLM 响应中不包含 tool call — 模型认为当前任务完成
2. Steering 队列为空 — 没有外部注入的新指令
3. Follow-up 队列为空 — 没有追加的后续任务
4. 未发生错误（`StopReason.ERROR`）且未被中止（`StopReason.ABORTED`）

## 工具执行机制

### AgentTool 接口

每个工具实现以下契约：

```java
public interface AgentTool {
    String name();                          // 工具唯一标识
    String label();                         // 展示名称
    String description();                   // 功能描述（LLM 用于决策）
    Map<String, Object> parametersSchema(); // JSON Schema（参数校验）
    CompletableFuture<AgentToolResult> execute(
        String toolCallId,
        Map<String, Object> params,
        AgentToolUpdateCallback onUpdate,     // 进度回调
        CancellationException cancellation    // 取消信号
    );
}
```

LLM 通过 `name` 和 `description` 决定调用哪个工具，通过 `parametersSchema` 生成符合 schema 的参数。

### 执行模式

通过 `ToolExecutionMode` 控制同一轮中多个 tool call 的执行策略：

| 模式 | 行为 | 适用场景 |
|------|------|----------|
| `PARALLEL`（默认） | 多个 tool call 用 `CompletableFuture` 并行执行 | 独立的查询/计算 |
| `SEQUENTIAL` | 按顺序逐个执行，前一个完成后才执行下一个 | 有依赖关系的操作 |

### 单个工具调用的完整生命周期

`runSingleToolCall()` 方法实现了工具执行的 7 个阶段：

```
1. emit(ToolExecutionStart)                ← 发出开始事件
2. 按 name 匹配工具                         ← 找不到工具返回错误结果
3. ToolArgumentValidator.validate()         ← 用 JSON Schema 校验参数
4. options.beforeToolCall().apply()          ← 前置钩子（可拦截执行）
5. tool.execute(id, validatedArgs, ...)     ← 异步执行工具
6. options.afterToolCall().apply()           ← 后置钩子（可修改结果）
7. emit(ToolExecutionEnd)                   ← 发出完成事件
```

**参数校验**：`ToolArgumentValidator` 在执行前验证 LLM 生成的参数是否符合声明的 JSON Schema。校验失败会返回错误 tool result，LLM 在下一轮推理中看到错误信息后可以修正参数重试。

**钩子拦截**：`beforeToolCall` 可以返回 `block=true` 来阻止工具执行，适用于权限控制、危险操作拦截等场景。

## 消息队列：Steering 和 Follow-up

ReAct 循环支持运行时消息注入，这是超越论文原始 ReAct 的增强能力。

### Steering（转向）

在 agent 执行过程中从外部注入消息，当前 turn 结束后立即处理：

```java
// 任意线程随时调用
agent.steer(new AgentUserMessage("请用中文回答"));

// Agent 内部：每个 turn 结束时检查
pending = getSteeringMessages();
// 非空则作为新的用户消息，触发下一轮 Reasoning
```

用途：实时修正 agent 行为方向，而不需要等待整个循环结束。

### Follow-up（追问）

当 agent 完成所有 tool call 并准备结束时，检查 follow-up 队列：

```java
List<AgentMessage> followUps = getFollowUpMessages();
if (followUps.isEmpty()) {
    break;     // 真正结束
}
pending = followUps;  // 作为新一轮 ReAct 的起点
```

用途：在 agent 完成一个任务后自动追加新任务，实现任务链。

### 队列消费模式

通过 `QueueMode` 控制每次消费的消息数量：

| 模式 | 行为 |
|------|------|
| `ALL` | 一次性取出队列中全部消息 |
| `ONE_AT_A_TIME` | 每次只取一条，剩余留给下一轮 |

## 事件流模型

ReAct 循环的每个阶段都发出事件，支持实时观测和 SSE 推送：

```
AgentStart
  └─ TurnStart                          ← 第 1 轮
       ├─ MessageStart(user)            ← 用户输入
       ├─ MessageEnd(user)
       ├─ MessageStart(assistant)       ← LLM 开始推理 (Reasoning)
       ├─ MessageUpdate(assistant) ×N   ← 流式 delta token
       ├─ MessageEnd(assistant)         ← 推理完成，包含 tool calls
       ├─ ToolExecutionStart            ← 开始执行工具 (Acting)
       ├─ ToolExecutionUpdate           ← 工具执行进度
       ├─ ToolExecutionEnd              ← 工具执行完成 (Observation)
       ├─ MessageStart(toolResult)
       └─ MessageEnd(toolResult)
  └─ TurnEnd
  └─ TurnStart                          ← 第 2 轮（自动，因为有 tool results）
       ├─ MessageStart(assistant)       ← LLM 继续推理
       ├─ MessageUpdate(assistant) ×N
       └─ MessageEnd(assistant)         ← 无 tool call → 输出最终答案
  └─ TurnEnd
AgentEnd(messages)
```

## 与 ReAct 论文的对应关系

| 论文概念 | 框架实现 | 代码位置 |
|----------|----------|----------|
| Thought (思考) | `AgentAssistantMessage` 中的 `TextContent` | LLM 流式响应文本 |
| Action (行动) | `ToolCallContent` — LLM 生成的 tool call 指令 | `assistant.content()` 中提取 |
| Observation (观察) | `AgentToolResultMessage` — 工具执行结果 | `tool.execute()` 返回值 |
| Thought → Action → Observation 循环 | `Agent.runLoop()` 的 while 循环 | `Agent.java:141-194` |
| 终止条件 | LLM 不再生成 tool call | `hasMoreToolCalls = !toolCalls.isEmpty()` |
| Action Space (动作空间) | 注册的 `AgentTool` 列表 | `state.tools()` |

## 扩展点

框架在 ReAct 循环的关键节点提供了可编程的扩展钩子：

| 钩子 | 时机 | 用途示例 |
|------|------|----------|
| `transformContext` | LLM 调用前 | token 裁剪、上下文窗口管理 |
| `convertToLlm` | 消息格式转换 | 自定义消息映射、过滤敏感内容 |
| `beforeToolCall` | 工具执行前 | 权限检查、参数改写、危险操作拦截 |
| `afterToolCall` | 工具执行后 | 结果过滤、审计日志、敏感数据脱敏 |
| `steeringMessagesSupplier` | turn 结束时 | 从外部系统拉取实时指令 |
| `followUpMessagesSupplier` | agent 结束前 | 从任务队列拉取后续任务 |
| `apiKeyResolver` | LLM 调用前 | 按 provider 动态获取 API Key |
