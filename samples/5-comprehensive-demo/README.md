# 案例 5: 综合功能演示

端到端演示一个贴近真实业务的场景，串联所有核心功能：Skills、Sandbox、多 Namespace、多用户。

## 场景：SaaS 平台的 AI 编程助手

一个 SaaS 平台为不同企业客户提供 AI 编程助手服务。每个客户有独立的命名空间、自定义工具链、独立的沙箱环境。

### 角色

| 企业 (Namespace) | 用户 | 角色 | 工作任务 |
|---|---|---|---|
| `acme-corp` | Dave | 后端工程师 | 开发 Java 微服务 |
| `acme-corp` | Eve | DevOps | 部署和监控 |
| `startup-xyz` | Frank | 全栈工程师 | 开发 Node.js 应用 |

### Skills 分布

```
skills/
├── public/                          ← 所有客户可用
│   ├── code-review/SKILL.md
│   └── git-workflow/SKILL.md
├── namespaces/
│   ├── acme-corp/                   ← Acme Corp 专有
│   │   ├── java-build/
│   │   │   ├── SKILL.md
│   │   │   └── build.sh
│   │   ├── deploy-k8s/
│   │   │   ├── SKILL.md
│   │   │   └── deploy.sh
│   │   └── code-review/             ← 覆盖公共版本
│   │       └── SKILL.md
│   └── startup-xyz/                 ← Startup XYZ 专有
│       ├── npm-build/
│       │   ├── SKILL.md
│       │   └── build.sh
│       └── deploy-vercel/
│           ├── SKILL.md
│           └── deploy.sh
```

## 完整工作流

### Phase 1: 初始化环境

```bash
# 1. 准备 Skills
./setup-comprehensive.sh

# 2. 重载 Catalog
curl -s -X POST http://localhost:8080/api/rpc/catalog/reload | jq .
```

### Phase 2: Acme Corp — Dave 开发微服务

```bash
# Dave 创建 Session
DAVE_RESP=$(curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "dave-1",
    "type": "new_session",
    "namespace": "acme-corp",
    "sessionName": "dave-user-service",
    "provider": "openai",
    "modelId": "gpt-4o-mini",
    "systemPrompt": "You are a Java backend assistant. Use the available skills."
  }')
DAVE_SID=$(echo $DAVE_RESP | jq -r '.data.sessionId')

# Dave 查看可用工具
curl -s "http://localhost:8080/api/rpc/catalog/skills?namespace=acme-corp" | jq '.[].name'
# → "code-review" (acme 定制版), "git-workflow", "java-build", "deploy-k8s"

# Dave 在沙箱中编写代码
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"dave-2\",
    \"type\": \"bash\",
    \"sessionId\": \"$DAVE_SID\",
    \"namespace\": \"acme-corp\",
    \"command\": \"cat > /workspace/UserService.java << 'JAVA'\npublic class UserService {\n    public User findById(long id) {\n        return userRepo.findById(id).orElseThrow();\n    }\n}\nJAVA\necho '--- File created ---'\ncat /workspace/UserService.java\"
  }" | jq '.data.stdout'

# Dave 请求 AI 做 Code Review（使用 Acme 定制规范）
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d "{
    \"namespace\": \"acme-corp\",
    \"sessionId\": \"$DAVE_SID\",
    \"prompt\": \"请对 /workspace/UserService.java 进行 code review\",
    \"provider\": \"openai\",
    \"modelId\": \"gpt-4o-mini\"
  }"
```

### Phase 3: Acme Corp — Eve 部署服务

```bash
# Eve 创建独立 Session（同 namespace，不同用户）
EVE_RESP=$(curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "eve-1",
    "type": "new_session",
    "namespace": "acme-corp",
    "sessionName": "eve-deploy"
  }')
EVE_SID=$(echo $EVE_RESP | jq -r '.data.sessionId')

# Eve 使用 deploy-k8s skill
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d "{
    \"namespace\": \"acme-corp\",
    \"sessionId\": \"$EVE_SID\",
    \"prompt\": \"请帮我把 user-service 部署到 staging 环境\",
    \"provider\": \"openai\",
    \"modelId\": \"gpt-4o-mini\"
  }"

# Eve 看不到 Dave 沙箱中的文件
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"eve-2\",
    \"type\": \"bash\",
    \"sessionId\": \"$EVE_SID\",
    \"namespace\": \"acme-corp\",
    \"command\": \"ls /workspace/\"
  }" | jq '.data.stdout'
# → 空或只有 Eve 自己的文件
```

### Phase 4: Startup XYZ — Frank 独立工作

```bash
# Frank 创建 Session（不同 namespace）
FRANK_RESP=$(curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "frank-1",
    "type": "new_session",
    "namespace": "startup-xyz",
    "sessionName": "frank-fullstack"
  }')
FRANK_SID=$(echo $FRANK_RESP | jq -r '.data.sessionId')

# Frank 看到的 Skills 完全不同
curl -s "http://localhost:8080/api/rpc/catalog/skills?namespace=startup-xyz" | jq '.[].name'
# → "code-review" (公共版), "git-workflow", "npm-build", "deploy-vercel"
# 注意: 没有 java-build, deploy-k8s（acme-corp 专有）

# Frank 在沙箱中工作
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"frank-2\",
    \"type\": \"bash\",
    \"sessionId\": \"$FRANK_SID\",
    \"namespace\": \"startup-xyz\",
    \"command\": \"echo '{\\\"name\\\": \\\"startup-app\\\"}' > /workspace/package.json && cat /workspace/package.json\"
  }" | jq '.data.stdout'

# Frank 不能访问 Acme Corp 的 session
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"frank-3\",
    \"type\": \"get_state\",
    \"sessionId\": \"$DAVE_SID\",
    \"namespace\": \"startup-xyz\"
  }" | jq .
# → success: false, "Namespace mismatch"
```

### Phase 5: 验证隔离性

```bash
echo "=== 最终隔离验证 ==="

# 1. Skills 隔离
echo "--- Skills 可见性 ---"
echo "acme-corp:"
curl -s "http://localhost:8080/api/rpc/catalog/skills?namespace=acme-corp" | jq -r '.[].name'
echo ""
echo "startup-xyz:"
curl -s "http://localhost:8080/api/rpc/catalog/skills?namespace=startup-xyz" | jq -r '.[].name'

# 2. Session 隔离
echo ""
echo "--- Session 隔离 ---"
echo "Dave session 属于 acme-corp，Frank (startup-xyz) 无法访问"

# 3. 工作空间隔离
echo ""
echo "--- 工作空间隔离 ---"
echo "Dave, Eve, Frank 各自有独立的 /workspace/"
```

## 架构总结

```
                    ┌─────────────────────────────────────────┐
                    │         Pi Agent Framework              │
                    │                                         │
 ┌──────────┐      │  ┌─────────────────────────────────┐   │
 │  Dave    ├──────┤  │         acme-corp               │   │
 │(acme)    │      │  │  Skills: code-review(custom),   │   │
 │          │      │  │          java-build, deploy-k8s │   │
 └──────────┘      │  │  Sessions: dave-*, eve-*        │   │
 ┌──────────┐      │  │  Workspace: /ws/acme-corp/...   │   │
 │  Eve     ├──────┤  └─────────────────────────────────┘   │
 │(acme)    │      │                                         │
 └──────────┘      │  ┌─────────────────────────────────┐   │
                    │  │         startup-xyz              │   │
 ┌──────────┐      │  │  Skills: code-review(public),   │   │
 │  Frank   ├──────┤  │          npm-build, deploy-vercel│  │
 │(startup) │      │  │  Sessions: frank-*               │   │
 └──────────┘      │  │  Workspace: /ws/startup-xyz/...  │   │
                    │  └─────────────────────────────────┘   │
                    │                                         │
                    │  ┌─────────────┐  ┌────────────────┐  │
                    │  │ Public      │  │   Docker       │  │
                    │  │ Skills:     │  │   Sandbox      │  │
                    │  │ code-review │  │   (per session) │  │
                    │  │ git-workflow│  └────────────────┘  │
                    │  └─────────────┘                       │
                    └─────────────────────────────────────────┘
```

## 关键隔离点

| 层级 | 隔离范围 | 机制 |
|------|---------|------|
| **Namespace** | 企业客户之间 | Skills 可见性过滤 + Session namespace 校验 |
| **Session** | 用户之间 | 独立 sessionId + 工作空间目录隔离 |
| **Sandbox** | 命令执行 | Docker 容器隔离（网络/文件系统/资源限制） |
| **Skills** | 工具链定制 | public + namespace 私有 + 同名覆盖 |