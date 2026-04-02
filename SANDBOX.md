# 执行沙箱 (Sandbox) 原理

本文档说明 delphi-agent 中命令执行沙箱的设计原理、安全模型和实现细节。

## 设计目标

在多租户场景下，Agent 需要执行用户提供的命令（bash、脚本等），这带来严重的安全风险。执行沙箱的目标是：

1. **租户隔离** — 租户 A 的命令不能访问租户 B 的数据
2. **路径限制** — 命令只能操作指定的 workspace 目录
3. **资源限制** — 防止资源耗尽（CPU、内存、进程数）
4. **网络隔离** — 防止数据外泄
5. **审计追踪** — 所有执行操作可追溯

## 架构

```
┌──────────────────────────────────────────────────────────┐
│                   调用方                                  │
│  RpcCommandProcessor.commandBash()                       │
│  SkillAgentTool.executeViaBackend()                      │
└────────────────────┬─────────────────────────────────────┘
                     │ ExecutionContext(namespace, sessionId, userId)
                     ▼
┌──────────────────────────────────────────────────────────┐
│              ExecutionBackend (接口)                      │
│                                                          │
│  execute(context, command, options) → ExecutionResult     │
│  readFile(context, relativePath) → String                │
│  writeFile(context, relativePath, content)               │
│  getWorkspacePath(namespace, sessionId) → Path           │
│  cleanupSession(namespace, sessionId)                    │
└────────────┬─────────────────────────┬───────────────────┘
             │                         │
    ┌────────▼────────┐      ┌────────▼────────┐
    │ Docker Isolated │      │ Local Isolated  │
    │    Backend      │      │    Backend      │
    │                 │      │                 │
    │ @Profile        │      │ @Profile        │
    │ ("!local-dev")  │      │ ("local-dev")   │
    │                 │      │                 │
    │ 默认生效          │      │ 仅开发模式       │
    │ 容器级隔离        │      │ 目录级隔离        │
    └─────────────────┘      └─────────────────┘
```

## 核心抽象

### ExecutionContext

执行上下文，携带隔离所需的身份标识：

```java
public record ExecutionContext(
    String namespace,    // 租户标识，决定 workspace 的一级目录
    String sessionId,    // 会话标识，决定 workspace 的二级目录
    String userId        // 操作者标识（预留，用于审计）
) {}
```

### ExecutionOptions

执行约束参数：

```java
public record ExecutionOptions(
    long timeoutMs,              // 超时（默认 30 秒）
    int maxOutputBytes,          // 输出上限（默认 1MB）
    Map<String, String> envVars  // 环境变量
) {}
```

### ExecutionResult

执行结果：

```java
public record ExecutionResult(
    int exitCode,       // 进程退出码
    String stdout,      // 标准输出（可能被截断）
    String stderr,      // 标准错误（可能被截断）
    long durationMs,    // 执行耗时
    boolean timeout,    // 是否超时
    boolean truncated   // 输出是否被截断
) {
    public boolean isSuccess() {
        return exitCode == 0 && !timeout;
    }
}
```

## Workspace 目录结构

每个 namespace + session 组合拥有独立的工作目录：

```
workspaces/                          ← workspacesRoot（可配置）
├── tenant-a/                        ← namespace 级目录
│   ├── session-abc123/              ← session 级目录（工具执行的工作目录）
│   │   ├── src/
│   │   ├── output.txt
│   │   └── ...
│   └── session-def456/
│       └── ...
├── tenant-b/
│   └── session-xyz789/
│       └── ...
```

## DockerIsolatedBackend — 生产级隔离

默认激活（`@Profile("!local-dev")`）。每次命令执行在独立的 Docker 容器中运行。

### Docker 运行参数

```java
String[] buildDockerCommand(ExecutionContext context, String command, ExecutionOptions options) {
    List<String> cmd = new ArrayList<>();
    cmd.add("docker"); cmd.add("run"); cmd.add("--rm");

    // 安全策略
    cmd.add("--network"); cmd.add("none");           // 禁止网络访问
    cmd.add("--user"); cmd.add("65534:65534");       // nobody 用户（非 root）
    cmd.add("--read-only");                          // 只读根文件系统

    // 资源限制
    cmd.add("--memory"); cmd.add(memoryLimit);       // 内存上限（默认 256m）
    cmd.add("--cpu-quota"); cmd.add(...);            // CPU 配额（默认 50000/100000）
    cmd.add("--pids-limit"); cmd.add(...);           // 进程数上限（默认 100）

    // 临时文件系统
    cmd.add("--tmpfs"); cmd.add("/tmp:rw,noexec,nosuid,size=64m");

    // 仅挂载当前会话的 workspace
    cmd.add("-v"); cmd.add(workspace + ":/workspace:rw");
    cmd.add("-w"); cmd.add("/workspace");

    // 环境变量
    for (var entry : options.envVars().entrySet()) {
        cmd.add("-e"); cmd.add(entry.getKey() + "=" + entry.getValue());
    }

    cmd.add(dockerImage);
    cmd.add("bash"); cmd.add("-c"); cmd.add(command);
    return cmd.toArray(new String[0]);
}
```

### 安全层级

| 层级 | 机制 | 防护目标 |
|------|------|----------|
| 网络隔离 | `--network none` | 防止数据外泄、反弹 shell |
| 用户隔离 | `--user 65534:65534` | 防止容器内提权 |
| 文件系统隔离 | `--read-only` + 单目录挂载 | 防止读写宿主机文件 |
| 内存限制 | `--memory 256m` | 防止 OOM 影响宿主机 |
| CPU 限制 | `--cpu-quota 50000` | 防止 CPU 独占 |
| 进程限制 | `--pids-limit 100` | 防止 fork 炸弹 |
| 临时文件 | `--tmpfs /tmp:noexec,nosuid` | 防止上传可执行文件 |
| 自动清理 | `--rm` | 容器执行后自动销毁 |

### 执行流程

```
1. validatePathSegment(namespace)     ← 拦截路径穿越
2. validatePathSegment(sessionId)     ← 拦截路径穿越
3. Files.createDirectories(workspace) ← 创建 workspace
4. buildDockerCommand(...)            ← 构建 docker run 命令
5. ProcessBuilder(dockerCmd).start()  ← 启动容器
6. process.waitFor(timeoutMs)         ← 等待执行（超时则 destroyForcibly）
7. readStream(stdout, maxBytes)       ← 读取输出（超限则截断）
8. readStream(stderr, maxBytes)
9. → ExecutionResult                  ← 返回结果
```

### 配置

```yaml
pi:
  execution:
    workspaces-root: /data/workspaces
    docker:
      image: ubuntu:22.04          # 沙箱镜像
      cpu-quota: 50000             # CPU 配额（微秒/100ms 周期）
      memory-limit: 256m           # 内存限制
      pids-limit: 100              # 最大进程数
```

## LocalIsolatedBackend — 开发模式

仅在 `local-dev` profile 下激活（`@Profile("local-dev")`）。直接通过 `ProcessBuilder` 在宿主机上执行命令。

### 执行方式

```java
ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
pb.directory(workspace.toFile());         // 工作目录限定在 workspace
pb.environment().putAll(options.envVars());
```

### 与 Docker 模式的对比

| 能力 | Docker 模式 | Local 模式 |
|------|-------------|------------|
| 进程隔离 | 容器级 | 无（直接宿主机进程） |
| 网络隔离 | `--network none` | 无 |
| 文件系统隔离 | 只读 root + 单目录挂载 | 工作目录限定 + 路径校验 |
| 资源限制 | CPU/Memory/PIDs | 仅超时 |
| 用户隔离 | nobody (65534) | 当前进程用户 |
| 路径穿越防护 | 有（字符检测 + 归一化） | 有（字符检测 + 归一化） |
| 审计日志 | 有 | 有 |

## 路径穿越防护

系统实现了三层纵深防御：

### 第一层：RPC 入口校验

`RpcCommandProcessor` 在所有 RPC 命令的入口处对 `sessionId` 和 `namespace` 进行路径穿越检查，
确保恶意输入在到达任何业务逻辑之前即被拦截：

```java
private String requireSessionId(RpcCommandRequest request) {
    String sessionId = request.getSessionId();
    if (sessionId == null || sessionId.isBlank()) {
        throw new IllegalArgumentException("sessionId is required");
    }
    validateNoPathTraversal("sessionId", sessionId);
    return sessionId;
}

private void validateNoPathTraversal(String field, String value) {
    if (value.contains("..") || value.contains("/")
            || value.contains("\\") || value.contains("\0")) {
        throw new IllegalArgumentException(
            "Invalid " + field + ": path traversal characters detected");
    }
}
```

`requireNamespace` 同样包含该校验。这一层保证了即使后续逻辑（如 session 查找）不检查路径穿越，
恶意请求也无法通过。

### 第二层：ExecutionBackend 字符黑名单

```java
private void validatePathSegment(String field, String value) {
    if (value.contains("..") || value.contains("/")
            || value.contains("\\") || value.contains("\0")) {
        throw new IllegalArgumentException(
            "Invalid " + field + ": path traversal characters detected");
    }
}
```

拦截 namespace 和 sessionId 中的 `..`、`/`、`\`、空字节。与 RPC 层校验形成纵深防御。

### 第三层：路径归一化校验

```java
// LocalIsolatedBackend 额外校验
Path resolved = workspacesRoot.resolve(value).normalize();
if (!resolved.startsWith(workspacesRoot)) {
    throw new IllegalArgumentException("Invalid " + field + ": escapes workspace root");
}
```

对于文件读写操作，额外校验相对路径不会逃逸：

```java
private Path resolveAndValidatePath(ExecutionContext context, String relativePath) {
    Path workspace = getWorkspacePath(context.namespace(), context.sessionId());
    Path resolved = workspace.resolve(relativePath).normalize();
    if (!resolved.startsWith(workspace)) {
        throw new SecurityException("Path traversal detected: " + relativePath);
    }
    return resolved;
}
```

## 审计日志

`ExecutionAuditLogger` 记录每次执行和文件访问：

```java
// 命令执行审计
AUDIT: namespace=tenant-a sessionId=abc123 userId=null
       command=ls -la exitCode=0 durationMs=50 timeout=false truncated=false

// 文件访问审计
AUDIT: namespace=tenant-a sessionId=abc123 userId=null
       operation=READ path=output.txt
```

命令内容超过 200 字符会被截断，防止日志膨胀。

## Profile 切换

| 启动方式 | 激活的后端 |
|----------|-----------|
| `java -jar server.jar` | `DockerIsolatedBackend`（默认） |
| `java -jar server.jar --spring.profiles.active=local-dev` | `LocalIsolatedBackend` |
| `mvn spring-boot:run` | `DockerIsolatedBackend`（默认） |
| `mvn spring-boot:run -Dspring-boot.run.profiles=local-dev` | `LocalIsolatedBackend` |

生产环境不配置任何 profile 即自动使用 Docker 隔离，确保默认安全。

## 调用入口

沙箱通过以下入口被调用：

| 入口 | 调用方式 |
|------|----------|
| RPC `bash` 命令 | `RpcCommandProcessor.commandBash()` → `executionBackend.execute()` |
| Skill 脚本执行 | `SkillAgentTool.executeViaBackend()` → `executionBackend.execute()` |
| 文件读取 | `executionBackend.readFile()` |
| 文件写入 | `executionBackend.writeFile()` |
| 会话清理 | `executionBackend.cleanupSession()` |

所有入口都经过两道关卡：
1. `RpcCommandProcessor` 的路径穿越校验（`requireSessionId` / `requireNamespace`）
2. `AgentSessionRuntime.validateNamespace` 的 namespace 归属校验

之后才到达 `ExecutionBackend`。

## 测试覆盖

| 测试类 | 覆盖场景 |
|--------|----------|
| `LocalIsolatedBackendTest` | `..` 穿越、`/` 穿越、`\0` 注入、`\` 穿越、readFile 穿越、writeFile 穿越、workspace 正确性、跨 namespace 隔离 |
| `DockerIsolatedBackendTest` | Docker 命令构建验证（网络/用户/内存/CPU/pids/只读/挂载）、路径穿越拦截、环境变量传递 |
