# Delphi Agent 技术架构

本文档集中描述 `delphi-agent` 的主要技术架构图，包括整体调用链路、运行时架构、Agent loop、工具与 skills、sandbox 技术架构和数据流。

## 1. 整体架构流程图

```mermaid
flowchart TD
    Client["Client / Platform"] -->|POST /api/chat/stream<br/>SSE| HttpApi["delphi-agent-http-api"]

    subgraph HttpLayer["HTTP API Layer"]
        HttpApi --> Identity["RuntimeIdentityResolver<br/>X-Tenant-Id / X-User-Id / namespace"]
        Identity --> StreamController["StreamChatController<br/>prompt / command 分发"]
        StreamController --> CatalogController["CatalogController<br/>models / skills / prompts / resources"]
        StreamController --> AuditUsage["AuditController / UsageController"]
    end

    subgraph RuntimeLayer["Runtime Layer"]
        StreamController --> RunRuntime["AgentRunRuntime<br/>runId / queue / active run / SSE event"]
        RunRuntime --> TenantGuard["TenantRuntimeGuard<br/>namespace / quota / queue capacity"]
        RunRuntime --> LiveRegistry["LiveRunRegistry<br/>active run / abort / steer"]
        RunRuntime --> QueueManager["RunQueueManager<br/>interrupt / followup / steer / drop / reject"]
        RunRuntime --> SessionRuntime["AgentSessionRuntime<br/>session lifecycle / persistence / compact / fork"]
        SessionRuntime --> ToolFactory["AgentToolFactory<br/>tool inventory + policy"]
    end

    subgraph CoreLayer["Agent Core Layer"]
        SessionRuntime --> Agent["Agent.runLoop<br/>ReAct turn loop"]
        Agent --> AgentState["AgentState<br/>messages / tools / model / streaming"]
        Agent --> ToolCalls["AgentTool.execute<br/>tool call execution"]
        Agent --> AiRuntime["AiRuntime.streamSimple"]
    end

    subgraph ProviderLayer["Provider Layer"]
        AiRuntime --> ProviderRegistry["ApiProviderRegistry"]
        ProviderRegistry --> SpringAiProvider["SpringAiChatModelProvider"]
        SpringAiProvider --> ModelProvider["Model Provider<br/>DeepSeek Anthropic-compatible endpoint"]
    end

    subgraph ToolLayer["Tool / Sandbox Layer"]
        ToolFactory --> BuiltinTools["Builtin Tools<br/>read / write / edit / bash / grep / find / ls"]
        ToolFactory --> SkillTools["SkillAgentTool<br/>instructional / executable"]
        ToolFactory --> SubagentTools["Subagent Tools<br/>spawn / status / result / abort"]
        BuiltinTools --> Sandbox["ExecutionBackend"]
        SkillTools --> Sandbox
        SubagentTools --> SubagentRuntime["SubagentRuntime"]
    end

    subgraph StorageLayer["Storage / Catalog"]
        SessionRuntime --> Mongo["MongoDB<br/>sessions / entries / audit / usage"]
        ToolFactory --> Catalog["ResourceCatalogService<br/>skills / prompts / resources"]
        Catalog --> SkillDirs["skills/public<br/>skills/namespaces/&lt;namespace&gt;"]
    end
```

## 2. 请求运行时序图

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant API as StreamChatController
    participant ID as RuntimeIdentityResolver
    participant RR as AgentRunRuntime
    participant TG as TenantRuntimeGuard
    participant SR as AgentSessionRuntime
    participant AF as AgentToolFactory
    participant A as Agent
    participant LLM as AiRuntime / Provider
    participant T as AgentTool
    participant S as ExecutionBackend
    participant DB as MongoDB

    C->>API: POST /api/chat/stream
    API->>ID: resolve tenant/user/namespace
    ID-->>API: RuntimeIdentity
    API->>SR: ensureSessionId(create if absent)
    SR->>DB: save/load session
    API->>RR: stream(AgentRunRequest)
    RR->>TG: validateRunContext + quota
    TG-->>RR: allowed
    RR-->>C: SSE run_started
    RR->>SR: configureRunTools
    SR->>AF: createTools(ToolRuntimeContext)
    AF-->>SR: builtin + skill + subagent tools after policy
    RR->>SR: prompt(sessionId, namespace, prompt)
    SR->>A: Agent.prompt()
    A->>LLM: streamSimple(model, context, tool definitions)
    LLM-->>A: text deltas + tool calls
    A-->>C: SSE message_delta
    A->>T: execute(toolCallId, args)
    T->>S: execute/readFile/writeFile if needed
    S-->>T: ExecutionResult / file content
    T-->>A: AgentToolResult
    A->>LLM: continue with tool result
    LLM-->>A: final answer
    A-->>SR: AgentEnd
    SR->>DB: persist messages / usage
    RR-->>C: SSE run_completed
```

## 3. Agent Loop 技术流程图

```mermaid
stateDiagram-v2
    [*] --> AgentStart
    AgentStart --> TurnStart
    TurnStart --> AddPendingMessages: user prompt / steering / follow-up
    AddPendingMessages --> StreamLLM
    StreamLLM --> EmitMessageDelta: text / thinking delta
    EmitMessageDelta --> StreamLLM
    StreamLLM --> AssistantDone
    AssistantDone --> HasToolCalls

    HasToolCalls --> ExecuteTools: yes
    ExecuteTools --> ValidateArgs
    ValidateArgs --> BeforeToolHook
    BeforeToolHook --> ToolExecute
    ToolExecute --> AfterToolHook
    AfterToolHook --> AppendToolResults
    AppendToolResults --> TurnEnd

    HasToolCalls --> TurnEnd: no
    TurnEnd --> CheckSteering
    CheckSteering --> TurnStart: steering exists
    CheckSteering --> CheckFollowUp: no steering
    CheckFollowUp --> TurnStart: follow-up exists
    CheckFollowUp --> AgentEnd: empty
    AgentEnd --> [*]

    StreamLLM --> AgentEnd: error / aborted
```

关键点：

- `Agent.runLoop()` 是核心 ReAct 循环，模型每一轮都可以返回文本和 tool calls。
- 工具调用前会经过 JSON Schema 参数校验和 `beforeToolCall` hook。
- 工具执行后会经过 `afterToolCall` hook，并以 tool result message 形式回灌上下文。
- steering 消息优先于 follow-up；二者都为空且模型不再调用工具时 run 结束。

## 4. 工具与 Skills 技术架构图

```mermaid
flowchart TD
    AgentRuntime["AgentSessionRuntime.configureRunTools"] --> Factory["AgentToolFactory"]
    Factory --> Inventory["ToolInventory.collect"]

    Inventory --> BuiltinFactory["BuiltinToolFactory"]
    BuiltinFactory --> ReadOnly["READONLY<br/>read / grep / find / ls"]
    BuiltinFactory --> Mutating["MUTATING<br/>write / edit"]
    BuiltinFactory --> Executable["EXECUTABLE<br/>bash"]

    Inventory --> SkillsResolver["SkillsResolver"]
    SkillsResolver --> PublicSkills["skills/public/**"]
    SkillsResolver --> NamespaceSkills["skills/namespaces/&lt;namespace&gt;/**"]
    PublicSkills --> SkillAgentTool["SkillAgentTool"]
    NamespaceSkills --> SkillAgentTool
    SkillAgentTool --> Instructional["Instructional Skill<br/>return SKILL.md as context"]
    SkillAgentTool --> ExecutableSkill["Executable Skill<br/>copy to workspace/.skills + run entrypoint"]

    Inventory --> TaskPlanning["TaskPlanningTool<br/>planner / orchestrator"]
    Factory --> SubagentToolFactory["SubagentOrchestrationToolFactory"]
    SubagentToolFactory --> SubagentTools["subagent_spawn / status / result / abort"]

    ReadOnly --> Policy["ToolPolicyPipeline"]
    Mutating --> Policy
    Executable --> Policy
    Instructional --> Policy
    ExecutableSkill --> Policy
    TaskPlanning --> Policy
    SubagentTools --> Policy

    Policy --> Resolver["TenantToolPolicyResolver<br/>role + tenant + depth"]
    Resolver --> WrappedTools["ToolExecutionWrapper / ToolAuditWrapper"]
    WrappedTools --> AgentTools["Agent.state().tools"]
```

工具策略按角色裁剪工具：

| 角色 | 允许类别 |
| --- | --- |
| `ORCHESTRATOR` | 默认全部；严格模式只允许 `READONLY`、`INSTRUCTIONAL`、`ORCHESTRATION` |
| `PLANNER` / `RESEARCHER` | `READONLY`、`INSTRUCTIONAL` |
| `REVIEWER` | `READONLY` |
| `CODER` | `READONLY`、`MUTATING`、`EXECUTABLE`、`INSTRUCTIONAL` |
| `TESTER` | `READONLY`、`EXECUTABLE`、`INSTRUCTIONAL` |

## 5. Sandbox 技术架构图

```mermaid
flowchart TD
    Tool["bash / executable skill / file tools"] --> Backend["ExecutionBackend"]
    Backend --> Context["ExecutionContext<br/>namespace / sessionId / userId"]
    Backend --> Options["ExecutionOptions<br/>timeoutMs / maxOutputBytes / envVars"]
    Context --> Workspace["Workspace Resolver<br/>workspaces/&lt;namespace&gt;/&lt;sessionId&gt;"]

    Backend --> Profile{"Spring Profile"}
    Profile -->|default !local-dev| DockerBackend["DockerIsolatedBackend"]
    Profile -->|local-dev| LocalBackend["LocalIsolatedBackend"]

    subgraph DockerSandbox["Docker Sandbox"]
        DockerBackend --> DockerCmd["docker run --rm"]
        DockerCmd --> NoNetwork["--network none"]
        DockerCmd --> NonRoot["--user 65534:65534"]
        DockerCmd --> ReadOnlyRoot["--read-only rootfs"]
        DockerCmd --> ResourceLimit["--memory / --cpu-quota / --pids-limit"]
        DockerCmd --> Tmpfs["--tmpfs /tmp:rw,noexec,nosuid,size=64m"]
        DockerCmd --> Mount["-v workspace:/workspace:rw"]
        DockerCmd --> Workdir["-w /workspace"]
        ResourceLimit --> TenantQuota["TenantQuotaManager<br/>per-tenant override"]
    end

    subgraph LocalSandbox["Local Dev Sandbox"]
        LocalBackend --> ProcessBuilder["ProcessBuilder bash -c"]
        ProcessBuilder --> LocalWorkdir["pb.directory(workspace)"]
        ProcessBuilder --> LocalTimeout["timeout control"]
    end

    Workspace --> PathGuard["Path Guards"]
    PathGuard --> SegmentGuard["namespace/sessionId reject<br/>.. / slash / backslash / null byte"]
    PathGuard --> NormalizeGuard["normalize + startsWith(workspace)"]
    NormalizeGuard --> FileOps["readFile / writeFile / resolvePathInWorkspace"]

    DockerBackend --> Result["ExecutionResult<br/>exitCode / stdout / stderr / duration / timeout / truncated"]
    LocalBackend --> Result
```

Docker sandbox 的安全边界：

- 进程隔离：每次执行独立 `docker run --rm`。
- 网络隔离：`--network none`。
- 用户隔离：容器内使用 `65534:65534` 非 root 用户。
- 文件系统隔离：只读 rootfs，仅挂载当前 session workspace。
- 资源限制：CPU、memory、PIDs 可配置并支持租户覆盖。
- 输出限制：stdout/stderr 按 `maxOutputBytes` 截断。

`local-dev` backend 只提供路径和超时级别保护，不提供容器隔离，只适合开发调试。

## 6. Catalog 与 Skill 加载流程

```mermaid
flowchart TD
    Config["pi.resources.* / env vars"] --> Catalog["ResourceCatalogService.reload"]
    Catalog --> SkillScan["scan skills dirs<br/>find SKILL.md"]
    Catalog --> PromptScan["scan prompts dirs<br/>*.prompt.md / *.prompt.txt / *.prompt"]
    Catalog --> ResourceScan["scan resources dirs<br/>regular files"]

    SkillScan --> ParseSkill["parse YAML frontmatter<br/>description / entrypoint / args_schema / timeout_ms"]
    ParseSkill --> SkillInfo["SkillInfo"]
    SkillInfo --> ScopeMatch["skillsByScope(scope)"]
    ScopeMatch --> Public["public"]
    ScopeMatch --> Namespace["namespaces/&lt;namespace&gt;"]
    Public --> Resolve["SkillsResolver.resolveSkills(namespace)"]
    Namespace --> Resolve
    Resolve --> Cache["namespace cache"]
    Cache --> ToolInventory["ToolInventory"]

    PromptScan --> PromptInfo["PromptTemplateInfo"]
    ResourceScan --> ResourceInfo["ResourceInfo"]
```

## 7. 会话与持久化架构图

```mermaid
flowchart TD
    SessionRuntime["AgentSessionRuntime"] --> Lifecycle["SessionLifecycleManager<br/>live agent cache / eviction"]
    SessionRuntime --> PromptQueue["SessionPromptQueue<br/>serialize same-session prompts"]
    SessionRuntime --> SessionRepo["SessionRepository"]
    SessionRuntime --> EntryRepo["SessionEntryRepository"]

    SessionRepo --> SessionDoc["SessionDocument<br/>namespace / projectKey / model / headEntryId / modes"]
    EntryRepo --> EntryDoc["SessionEntryDocument<br/>message / branch_summary / compaction"]

    SessionRuntime --> Prompt["prompt / cont"]
    Prompt --> Persist["persistConversation"]
    Persist --> EntryRepo
    Persist --> SessionRepo

    SessionRuntime --> Fork["forkSession"]
    Fork --> CopyPath["copy path entries to new session"]
    CopyPath --> EntryRepo

    SessionRuntime --> Navigate["navigateTree"]
    Navigate --> BranchSummary["summarize abandoned branch"]
    BranchSummary --> EntryRepo

    SessionRuntime --> Compact["compact"]
    Compact --> LlmSummary["LLM summary"]
    Compact --> HeuristicSummary["fallback heuristic summary"]
    LlmSummary --> Persist
    HeuristicSummary --> Persist
```

## 8. 部署与运行视图

```mermaid
flowchart LR
    User["User / Platform"] --> Server["delphi-agent-server<br/>Spring Boot"]
    Server --> Mongo["MongoDB"]
    Server --> Docker["Docker Engine<br/>default sandbox"]
    Server --> DeepSeek["DeepSeek Anthropic-compatible API"]
    Server --> SkillRoot["Skill Roots<br/>./skills, ~/.codex/skills"]
    Server --> WorkspaceRoot["Workspace Root<br/>./workspaces or PI_WORKSPACES_ROOT"]

    Docker --> Containers["One-off sandbox containers"]
    Containers --> WorkspaceMount["Mounted session workspace"]
```

最小运行依赖：

- JDK 21 + Maven 3.9+
- MongoDB
- `DEEPSEEK_API_KEY`
- Docker（默认 profile）

