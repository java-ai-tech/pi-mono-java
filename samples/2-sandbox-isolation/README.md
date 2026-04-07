# 案例 2: Sandbox 隔离执行

演示 Pi Agent Framework 的沙箱执行能力：容器隔离、资源限制、安全约束。

## 两种 ExecutionBackend

框架支持两种执行后端，通过 Spring Profile 切换：

| Backend | Profile | 隔离级别 | 适用场景 |
|---------|---------|---------|---------|
| **DockerIsolatedBackend** | 非 `local-dev` | 强隔离（容器） | 生产环境、多租户 SaaS |
| **LocalIsolatedBackend** | `local-dev` | 弱隔离（进程） | 本地开发、测试 |

### DockerIsolatedBackend（生产推荐）

- 网络隔离（`--network none`）
- 只读根文件系统（`--read-only`）
- 非特权用户（`--user 65534:65534`）
- CPU/内存/进程数限制
- 独立工作空间挂载

### LocalIsolatedBackend（开发便利）

- 本地进程执行（无容器开销）
- 工作空间目录隔离
- 路径穿越防护
- 超时保护
- **不隔离网络和文件系统**（开发时可访问本地资源）

## 前置条件

```bash
# 使用 DockerIsolatedBackend（默认）
mvn spring-boot:run -pl delphi-agent-server

# 使用 LocalIsolatedBackend（开发模式）
mvn spring-boot:run -pl delphi-agent-server -Dspring.profiles.active=local-dev
```

## 测试步骤

### Step 1: 基础沙箱执行

```bash
# 创建 session
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "req-1",
    "type": "new_session",
    "namespace": "sandbox-demo"
  }' | jq .
```

```bash
# 在沙箱中执行命令
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "req-2",
    "type": "bash",
    "sessionId": "<session-id>",
    "namespace": "sandbox-demo",
    "command": "echo hello && whoami && pwd"
  }' | jq .
```

预期输出：
```json
{
  "success": true,
  "data": {
    "stdout": "hello\nnobody\n/workspace\n",
    "exitCode": 0,
    "timeout": false
  }
}
```

### Step 2: 网络隔离验证

```bash
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "req-3",
    "type": "bash",
    "sessionId": "<session-id>",
    "namespace": "sandbox-demo",
    "command": "curl -s https://example.com || echo NETWORK_BLOCKED"
  }' | jq .
```

预期：命令失败，输出包含 `NETWORK_BLOCKED`。容器无法访问外部网络。

### Step 3: 只读文件系统验证

```bash
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "req-4",
    "type": "bash",
    "sessionId": "<session-id>",
    "namespace": "sandbox-demo",
    "command": "touch /etc/hacked 2>&1 || echo READONLY_FS"
  }' | jq .
```

预期：写入根文件系统失败，只有 `/workspace` 和 `/tmp` 可写。

### Step 4: 工作空间隔离验证

```bash
# 在 sandbox-demo 的 session 中写入文件
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "req-5",
    "type": "bash",
    "sessionId": "<session-id>",
    "namespace": "sandbox-demo",
    "command": "echo secret-data > /workspace/secret.txt && cat /workspace/secret.txt"
  }' | jq .

# 在另一个 namespace 的 session 中尝试读取 —— 不可访问
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "req-6",
    "type": "bash",
    "sessionId": "<other-session-id>",
    "namespace": "other-tenant",
    "command": "cat /workspace/secret.txt 2>&1 || echo FILE_NOT_FOUND"
  }' | jq .
```

预期：不同 namespace 的 session 拥有独立的 `/workspace` 目录，互不可见。

### Step 5: 超时保护验证

```bash
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "req-7",
    "type": "bash",
    "sessionId": "<session-id>",
    "namespace": "sandbox-demo",
    "command": "sleep 999"
  }' | jq .
```

预期：命令在 30 秒（默认超时）后被终止，返回 `timeout: true`。

### Step 6: 路径穿越防护

```bash
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "req-8",
    "type": "bash",
    "sessionId": "../../../etc",
    "namespace": "sandbox-demo",
    "command": "echo pwned"
  }' | jq .
```

预期：返回错误 `Invalid sessionId: path traversal characters detected`。

## Docker 命令解析

框架生成的 Docker 命令示例：

```bash
docker run --rm \
  --network none \              # 禁用网络
  --user 65534:65534 \          # 以 nobody 用户运行
  --read-only \                 # 只读根文件系统
  --memory 256m \               # 内存限制 256MB
  --cpu-quota 50000 \           # CPU 配额（50% 单核）
  --pids-limit 100 \            # 最大 100 个进程
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \  # 可写 /tmp（不可执行）
  -v /workspaces/tenant/session:/workspace:rw \  # 挂载工作空间
  -w /workspace \               # 工作目录
  ubuntu:22.04 \                # 基础镜像
  bash -c "echo hello"          # 用户命令
```

## 安全约束汇总

| 约束 | 配置 | 说明 |
|------|------|------|
| 网络隔离 | `--network none` | 容器无法访问任何网络 |
| 只读文件系统 | `--read-only` | 根文件系统不可写 |
| 非特权用户 | `--user 65534:65534` | 以 nobody 身份运行 |
| 内存限制 | `--memory 256m` | 默认 256MB，可配置 |
| CPU 限制 | `--cpu-quota 50000` | 默认 50% 单核，可配置 |
| 进程限制 | `--pids-limit 100` | 防止 fork bomb |
| 临时目录 | `--tmpfs /tmp:rw,noexec,nosuid,size=64m` | 可写但不可执行 |
| 超时保护 | 30s（默认） | 超时自动终止进程 |
| 路径穿越防护 | 服务端校验 | 拒绝包含 `..` / `\` 等字符的路径 |