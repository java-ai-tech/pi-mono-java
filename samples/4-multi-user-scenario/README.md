# 案例 4: 多用户场景

演示同一 Namespace 下多个用户并发使用的场景：独立 Session、共享 Skill、独立工作空间。

## 场景设计

团队 `team-alpha` 下有两个开发者同时工作：

| 用户 | 角色 | Session | 任务 |
|------|------|---------|------|
| Alice | 前端开发 | `alice-session` | 开发 React 组件 |
| Bob | 前端测试 | `bob-session` | 编写测试用例 |

两个用户：
- **共享** 同一 namespace (`team-alpha`) 的 Skills
- **独立** Session 和工作空间
- **互不干扰** 各自的 Agent 对话和命令执行

```
team-alpha/
├── Alice (Session A)                Bob (Session B)
│   ├── /workspace/A/ (沙箱隔离)     ├── /workspace/B/ (沙箱隔离)
│   ├── Skills: 共享 team-alpha      ├── Skills: 共享 team-alpha
│   ├── 对话: 独立                    ├── 对话: 独立
│   └── 模型: 可独立设置              └── 模型: 可独立设置
```

## 前置条件

本案例复用案例 1 的 Skills，无需额外的 setup 脚本。

```bash
# 启动服务
cd delphi-agent
mvn spring-boot:run -pl delphi-agent-server
```

## 测试步骤

### Step 1: 创建两个独立 Session

```bash
# Alice 创建 session
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "alice-1",
    "type": "new_session",
    "namespace": "team-alpha",
    "sessionName": "alice-dev",
    "provider": "openai",
    "modelId": "gpt-4o-mini"
  }' | jq .
# → 返回 sessionId: "alice-session-id"

# Bob 创建 session
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{
    "id": "bob-1",
    "type": "new_session",
    "namespace": "team-alpha",
    "sessionName": "bob-test",
    "provider": "openai",
    "modelId": "gpt-4o-mini"
  }' | jq .
# → 返回 sessionId: "bob-session-id"
```

### Step 2: 验证 Skills 共享

```bash
# Alice 和 Bob 看到的 Skills 列表相同
ALICE_SKILLS=$(curl -s 'http://localhost:8080/api/rpc/catalog/skills?namespace=team-alpha' | jq -S '.[].name')
BOB_SKILLS=$(curl -s 'http://localhost:8080/api/rpc/catalog/skills?namespace=team-alpha' | jq -S '.[].name')

echo "Alice Skills: $ALICE_SKILLS"
echo "Bob Skills:   $BOB_SKILLS"
# 两者完全相同
```

### Step 3: 独立执行命令

```bash
# Alice 在沙箱中创建文件
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"alice-2\",
    \"type\": \"bash\",
    \"sessionId\": \"<alice-session-id>\",
    \"namespace\": \"team-alpha\",
    \"command\": \"echo 'Alice component code' > /workspace/App.tsx && ls /workspace/\"
  }" | jq .

# Bob 在沙箱中创建文件（不同的工作空间）
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"bob-2\",
    \"type\": \"bash\",
    \"sessionId\": \"<bob-session-id>\",
    \"namespace\": \"team-alpha\",
    \"command\": \"echo 'Bob test code' > /workspace/App.test.tsx && ls /workspace/\"
  }" | jq .
```

预期：
- Alice 的 `/workspace/` 只有 `App.tsx`
- Bob 的 `/workspace/` 只有 `App.test.tsx`
- 两者互不可见

### Step 4: 独立模型设置

```bash
# Alice 切换为高级模型
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"alice-3\",
    \"type\": \"set_model\",
    \"sessionId\": \"<alice-session-id>\",
    \"namespace\": \"team-alpha\",
    \"provider\": \"openai\",
    \"modelId\": \"gpt-4-turbo\"
  }" | jq .

# Bob 保持轻量模型
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"bob-3\",
    \"type\": \"set_model\",
    \"sessionId\": \"<bob-session-id>\",
    \"namespace\": \"team-alpha\",
    \"provider\": \"openai\",
    \"modelId\": \"gpt-4o-mini\"
  }" | jq .

# 验证各自的状态
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"alice-4\",
    \"type\": \"get_state\",
    \"sessionId\": \"<alice-session-id>\",
    \"namespace\": \"team-alpha\"
  }" | jq '.data.model'
# → "gpt-4-turbo"

curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"bob-4\",
    \"type\": \"get_state\",
    \"sessionId\": \"<bob-session-id>\",
    \"namespace\": \"team-alpha\"
  }" | jq '.data.model'
# → "gpt-4o-mini"
```

### Step 5: 并发对话

```bash
# Alice 和 Bob 同时发起 prompt（模拟并发）
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"alice-5\",
    \"type\": \"prompt\",
    \"sessionId\": \"<alice-session-id>\",
    \"namespace\": \"team-alpha\",
    \"message\": \"帮我创建一个 React Button 组件\"
  }" &

curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"bob-5\",
    \"type\": \"prompt\",
    \"sessionId\": \"<bob-session-id>\",
    \"namespace\": \"team-alpha\",
    \"message\": \"帮我为 Button 组件编写测试\"
  }" &

wait
echo "两个 prompt 并发完成"
```

## 隔离保证

| 维度 | 隔离级别 | 说明 |
|------|---------|------|
| Session | 用户级 | 每个 session 独立的对话历史和状态 |
| 工作空间 | Session 级 | `workspaces/{namespace}/{sessionId}/` 目录隔离 |
| Skills | Namespace 级 | 同 namespace 用户共享 skills |
| 模型设置 | Session 级 | 每个 session 可独立配置模型和参数 |
| 沙箱 | Session 级 | 每次 bash 执行启动独立容器 |