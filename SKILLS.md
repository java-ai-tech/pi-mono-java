# Skills 执行原理

本文档说明 delphi-agent 中 Skills 系统的完整生命周期：从磁盘上的 `SKILL.md` 文件到 Agent 可调用的工具，再到沙箱中执行。

## 概述

Skills 是框架的可扩展能力单元。每个 Skill 是一个目录，包含一个 `SKILL.md` 文件，描述了该技能的功能和可选的执行入口。

Skills 系统实现了三个关键能力：
1. **Namespace 隔离** — 不同租户看到不同的技能集
2. **两种执行模式** — 指令型（LLM 指导）和 脚本型（沙箱执行）
3. **手动热重载** — 运行时更新技能无需重启，但需要显式调用 reload 接口

## 架构

```
磁盘文件                 内存模型                Agent 工具              沙箱执行

SKILL.md  ──────►  SkillInfo  ──────►  SkillAgentTool  ──────►  ExecutionBackend
(文件系统)     ①     (record)     ②      (AgentTool)      ③       (Docker/Local)
                        │
                        │ scope 匹配
                        ▼
               SkillsResolver
              (namespace 过滤)
```

① `ResourceCatalogService.readSkill()` — 解析 SKILL.md
② `StreamChatController` — 将 SkillInfo 包装为 AgentTool
③ `SkillAgentTool.executeViaBackend()` — 通过沙箱执行

## 阶段 1：Skill 加载

### 目录结构

```
skills/                               ← 技能根目录（可配置多个）
├── public/                           ← 所有 namespace 可见
│   ├── task-planning/
│   │   └── SKILL.md
│   └── code-review/
│       └── SKILL.md
└── namespaces/                       ← 按 namespace 隔离
    ├── tenant-a/
    │   └── deploy/
    │       ├── SKILL.md
    │       └── deploy.sh             ← 可执行脚本
    └── tenant-b/
        └── analytics/
            └── SKILL.md
```

### SKILL.md 格式

**指令型 Skill**（无 entrypoint）：
```markdown
# Code Review
Review code for security vulnerabilities and best practices.
Focus on OWASP Top 10 and input validation.
```

**可执行 Skill**（有 entrypoint）：
```markdown
---
entrypoint: "./deploy.sh"
args_schema: '{"type":"object","properties":{"env":{"type":"string"}}}'
---
# Deploy
Deploys the application to the specified environment.
```

YAML frontmatter 用 `---` 分隔，支持两个字段：
- `entrypoint` — 可执行脚本的路径（相对于 SKILL.md 所在目录）
- `args_schema` — 参数的 JSON Schema（可选，目前仅做元数据存储/返回，尚未直接用于 `SkillAgentTool.parametersSchema()`）
  - 支持单引号或双引号包裹的 JSON 字符串
  - 示例：`args_schema: '{"type":"object"}'` 或 `args_schema: "{\"type\":\"object\"}"`

### 加载过程

`ResourceCatalogService` 在启动时与 `POST /api/rpc/catalog/reload` 时扫描配置的技能目录：

```java
// 1. 遍历技能目录，找到所有 SKILL.md
private void loadSkills(Path root) {
    Files.walk(root)
        .filter(path -> path.getFileName().toString().equals("SKILL.md"))
        .forEach(this::readSkill);
}

// 2. 解析单个 SKILL.md
private void readSkill(Path path) {
    String content = Files.readString(path);
    String name = path.getParent().getFileName().toString();  // 目录名作为技能名
    String description = firstNonEmptyLine(content);          // 第一行作为描述
    String entrypoint = extractFrontmatterField(content, "entrypoint");
    String argsSchema = extractFrontmatterField(content, "args_schema");

    SkillInfo skill = new SkillInfo(name, description, path.toAbsolutePath().toString(),
                                     content, entrypoint, argsSchema);
    // 使用绝对路径作为 key，允许不同 scope 存在同名 skill
    skills.put(path.toAbsolutePath().toString(), skill);
}
```

### SkillInfo 数据模型

```java
public record SkillInfo(
    String name,          // 技能名（目录名，自动小写化）
    String description,   // 描述（SKILL.md 第一行非空文本）
    String path,          // SKILL.md 的绝对路径
    String content,       // SKILL.md 完整内容
    String entrypoint,    // 可执行脚本路径（可选）
    String argsSchema     // 参数 JSON Schema（可选）
) {
    public boolean isExecutable() {
        return entrypoint != null && !entrypoint.isBlank();
    }
}
```

### 配置

```yaml
pi:
  resources:
    skills-dirs: ./skills,~/.codex/skills   # 多个目录用逗号分隔
```

或通过环境变量：
```bash
export PI_AGENT_SKILLS_DIRS=./skills,/opt/shared-skills
```

## 阶段 2：Namespace 可见性解析

`SkillsResolver` 根据请求者的 namespace 组合出可见的技能集。

### 解析规则

```java
public List<SkillInfo> resolveSkills(String namespace) {
    return namespaceCache.computeIfAbsent(namespace, ns -> {
        Map<String, SkillInfo> merged = new LinkedHashMap<>();

        // 1. 先加载 public 技能
        for (SkillInfo skill : catalogService.skillsByScope("public")) {
            merged.put(skill.name(), skill);
        }

        // 2. 再加载 namespace 私有技能（同名覆盖 public）
        for (SkillInfo skill : catalogService.skillsByScope("namespaces/" + namespace)) {
            merged.put(skill.name(), skill);
        }

        return new ArrayList<>(merged.values());
    });
}
```

### Scope 匹配

`skillsByScope(scope)` 通过路径前缀匹配判断 skill 属于哪个 scope：

```java
private boolean matchesSkillScope(String skillPath, String scope) {
    // 将 skill 的绝对路径相对于技能根目录计算相对路径
    Path relative = absRoot.relativize(normalizedSkillPath);
    // 检查相对路径是否以 scope 开头
    // 例如：scope="public"，relative="public/task-planning/SKILL.md" → 匹配
    // 例如：scope="namespaces/tenant-a"，relative="namespaces/tenant-a/deploy/SKILL.md" → 匹配
    // 例如：scope="public"，relative="namespaces/public/..." → 不匹配（精确段匹配）
}
```

### 可见性矩阵

| 技能位置 | tenant-a 可见 | tenant-b 可见 | 说明 |
|----------|:---:|:---:|------|
| `public/shared/` | o | o | 公共技能，所有人可见 |
| `namespaces/tenant-a/private/` | o | x | tenant-a 私有 |
| `namespaces/tenant-b/custom/` | x | o | tenant-b 私有 |

### 缓存

解析结果按 namespace 缓存在 `ConcurrentHashMap` 中。以下场景会清除缓存：

- `POST /api/rpc/catalog/reload` — 重载 catalog 后自动清除全部缓存
- `POST /api/skills/reload?namespace=xxx` — 仅清除缓存（不重扫磁盘）

```java
public void invalidateCache(String namespace) {
    if (namespace == null) {
        namespaceCache.clear();       // 清除全部
    } else {
        namespaceCache.remove(namespace);  // 清除指定 namespace
    }
}
```

生效时机说明：
- 调用 `POST /api/rpc/catalog/reload` 成功返回后，下一次 skills 解析请求即使用最新磁盘内容。
- 正在执行中的请求不会被中途替换，变更对后续请求生效。

## 阶段 3：Skills → AgentTool 转换

在 `/api/chat/stream` 请求中，`StreamChatController` 将可见的 Skills 转换为 Agent 可调用的工具：

```java
// 1. 构建执行上下文
String conversationId = request.sessionId() != null ? request.sessionId()
    : "chat-" + System.currentTimeMillis();
ExecutionContext execCtx = new ExecutionContext(request.namespace(), conversationId, null);

// 2. 解析可见技能
List<SkillInfo> visibleSkills = skillsResolver.resolveSkills(request.namespace());

// 3. 转换为 AgentTool
List<AgentTool> tools = new ArrayList<>();
tools.add(new TaskPlanningTool());                    // 内置工具
for (SkillInfo skill : visibleSkills) {
    tools.add(new SkillAgentTool(skill, executionBackend, execCtx));  // Skill 工具
}

// 4. 注册到 Agent
agent.state().tools(tools);
```

转换后，LLM 看到的工具列表包含：

```json
[
  {"name": "task_planning", "description": "将复杂任务分解为可执行步骤..."},
  {"name": "skill_deploy", "description": "Execute skill script: deploy. Deploys the application."},
  {"name": "skill_code-review", "description": "Load skill instructions: code-review. Review code..."}
]
```

LLM 根据工具描述决定是否调用。

> 当前默认实现中，Skills 自动注入发生在 `/api/chat/stream` 路径。  
> `AgentSessionRuntime` 的 session 模式默认不会自动把 Skills 转成 tools 注入 Agent。

## 阶段 4：Skill 执行

`SkillAgentTool.execute()` 根据 skill 是否有 entrypoint 走不同路径：

### 可执行 Skill（有 entrypoint）

LLM 调用 `skill_deploy` 工具，传入参数 `{"input": "--env staging"}`：

```java
private AgentToolResult executeViaBackend(String input) {
    // 1. 将 skill 目录的文件复制到 workspace
    //    确保 Local 和 Docker 两种后端都能找到脚本
    Path skillDir = Paths.get(skill.path()).getParent();
    Path workspace = executionBackend.getWorkspacePath(...);
    // 复制 skillDir 下所有文件到 workspace

    // 2. 使用原始相对路径执行命令
    String command = skill.entrypoint() + " " + input;
    // → "./deploy.sh --env staging"

    // 3. 通过沙箱执行
    ExecutionResult result = executionBackend.execute(
        executionContext,              // namespace + sessionId
        command,                       // 实际命令
        ExecutionOptions.defaults()    // 30s 超时，1MB 输出限制
    );

    // 4. 返回结果
    if (result.isSuccess()) {
        return stdout;                 // 成功：返回标准输出
    } else {
        return stderr + exitCode;      // 失败：返回错误信息
    }
}
```

> **注意**：`executeViaBackend` 会先将 skill 目录中的所有文件（脚本、配置等）复制到执行 workspace 中，
> 然后使用 entrypoint 中的相对路径执行。这样无论是 Local 模式（直接在 workspace 目录执行）还是
> Docker 模式（只挂载 workspace 到容器内 `/workspace`），脚本都能被正确找到。

### 指令型 Skill（无 entrypoint）

LLM 调用 `skill_code-review` 工具，传入 `{"input": "review auth module"}`：

```java
private AgentToolResult returnAsInstructions(String input) {
    // 将 SKILL.md 内容和用户输入拼接返回
    String result = "[skill:" + skill.name() + "]\n"
        + skill.content()                    // SKILL.md 完整内容
        + "\n\n[user-input]\n" + input;      // 用户输入
    return new AgentToolResult(result, ...);
}
```

SKILL.md 的内容被注入到对话上下文中，LLM 在下一轮推理时会按照其中的指令行动。这种模式适合定义复杂的工作流程、检查清单、编码规范等。

### 执行模式判定

```java
public CompletableFuture<AgentToolResult> execute(...) {
    return CompletableFuture.supplyAsync(() -> {
        if (skill.isExecutable()) {
            return executeViaBackend(input);     // 走沙箱
        }
        return returnAsInstructions(input);      // 走指令注入
    });
}
```

`isExecutable()` 由 `entrypoint` 字段是否非空决定。

## 完整生命周期示例

以下展示一个可执行 Skill 从创建到执行的完整流程：

### 1. 创建 Skill

```bash
mkdir -p skills/namespaces/tenant-a/deploy
cat > skills/namespaces/tenant-a/deploy/SKILL.md << 'EOF'
---
entrypoint: "./run.sh"
---
# Deploy
Deploys the service to the target environment.
EOF

cat > skills/namespaces/tenant-a/deploy/run.sh << 'EOF'
#!/bin/bash
echo "Deploying to $1..."
echo "Deployment completed at $(date)"
EOF
chmod +x skills/namespaces/tenant-a/deploy/run.sh
```

### 2. 重载 Catalog

```bash
curl -X POST http://localhost:8080/api/rpc/catalog/reload
```

### 3. 验证可见性

```bash
# tenant-a 可以看到 deploy skill
curl http://localhost:8080/api/rpc/catalog/skills?namespace=tenant-a
# → [{"name":"deploy","description":"Deploy","entrypoint":"./run.sh",...}]

# tenant-b 看不到
curl http://localhost:8080/api/rpc/catalog/skills?namespace=tenant-b
# → []
```

### 4. 在 Chat 中使用

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "tenant-a",
    "prompt": "请帮我部署服务到 staging 环境",
    "provider": "deepseek",
    "modelId": "deepseek-chat"
  }'
```

LLM 分析 prompt 后决定调用 `skill_deploy` 工具：

```
event: tool_start
data: {"type":"tool_start","toolName":"skill_deploy","toolCallId":"call_1"}

event: tool_end
data: {"type":"tool_end","toolName":"skill_deploy","result":"Deploying to staging...\nDeployment completed at ...","isError":false}
```

## API 参考

| 接口 | 说明 |
|------|------|
| `GET /api/skills?namespace=xxx` | 查询 namespace 可见的 skills |
| `GET /api/skills/{name}?namespace=xxx` | 获取指定 skill 详情 |
| `POST /api/skills/reload?namespace=xxx` | 仅清理 namespace 的 skill 缓存（不重扫磁盘） |
| `GET /api/rpc/catalog/skills?namespace=xxx` | RPC 接口查询 skills（namespace 必填） |
| `POST /api/rpc/catalog/reload` | 重载全部 catalog + 清除全部 skill 缓存 |

## 源码导航

| 文件 | 职责 |
|------|------|
| `delphi-agent-runtime/.../catalog/SkillInfo.java` | Skill 数据模型（name, entrypoint, argsSchema 等） |
| `delphi-agent-runtime/.../catalog/ResourceCatalogService.java` | 从文件系统加载 skills，解析 frontmatter |
| `delphi-agent-runtime/.../catalog/SkillsResolver.java` | Namespace 可见性解析与缓存 |
| `delphi-agent-runtime/.../tools/SkillAgentTool.java` | Skill → AgentTool 适配器，execute 分派 |
| `delphi-agent-http-api/.../controller/StreamChatController.java` | 将 Skills 注入 Agent 工具列表 |
| `delphi-agent-http-api/.../controller/SkillsController.java` | Skills HTTP API |
| `delphi-agent-http-api/.../controller/RpcController.java` | RPC catalog API |
