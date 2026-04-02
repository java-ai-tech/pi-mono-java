# 案例 3: 多 Namespace 隔离

演示 Namespace 级别的资源隔离：Skill 可见性、Session 隔离、跨租户拒绝。

## 场景设计

模拟两个团队使用同一个 Pi Agent 实例：

| Namespace | 团队 | 私有 Skills | 公共 Skills |
|-----------|------|------------|------------|
| `team-alpha` | 前端团队 | `lint-frontend`, `build-react` | `code-review`, `task-planning` |
| `team-beta` | 后端团队 | `db-migrate`, `api-test` | `code-review`, `task-planning` |

```
skills/
├── public/                          ← 所有 namespace 可见
│   ├── code-review/
│   │   └── SKILL.md
│   └── task-planning/
│       └── SKILL.md
└── namespaces/
    ├── team-alpha/                   ← 仅 team-alpha 可见
    │   ├── lint-frontend/
    │   │   └── SKILL.md
    │   └── build-react/
    │       ├── SKILL.md
    │       └── build.sh
    └── team-beta/                    ← 仅 team-beta 可见
        ├── db-migrate/
        │   ├── SKILL.md
        │   └── migrate.sh
        └── api-test/
            ├── SKILL.md
            └── run-tests.sh
```

## 测试步骤

### Step 1: 准备多 Namespace Skills

```bash
./setup-namespaces.sh
curl -s -X POST http://localhost:8080/api/rpc/catalog/reload | jq .
```

### Step 2: 验证可见性矩阵

```bash
# team-alpha: 应看到 code-review + task-planning + lint-frontend + build-react (4个)
curl -s 'http://localhost:8080/api/rpc/catalog/skills?namespace=team-alpha' | jq '.[].name'

# team-beta: 应看到 code-review + task-planning + db-migrate + api-test (4个)
curl -s 'http://localhost:8080/api/rpc/catalog/skills?namespace=team-beta' | jq '.[].name'

# unknown-team: 只看到公共的 code-review + task-planning (2个)
curl -s 'http://localhost:8080/api/rpc/catalog/skills?namespace=unknown-team' | jq '.[].name'
```

预期输出：

```
# team-alpha
"code-review"
"task-planning"
"lint-frontend"
"build-react"

# team-beta
"code-review"
"task-planning"
"db-migrate"
"api-test"

# unknown-team
"code-review"
"task-planning"
```

### Step 3: 跨租户 Session 隔离

```bash
# 为 team-alpha 创建 session
ALPHA_RESP=$(curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{"id":"ns-1","type":"new_session","namespace":"team-alpha"}')
ALPHA_SID=$(echo $ALPHA_RESP | jq -r '.data.sessionId')

# team-beta 尝试访问 team-alpha 的 session —— 应被拒绝
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"ns-2\",
    \"type\": \"get_state\",
    \"sessionId\": \"$ALPHA_SID\",
    \"namespace\": \"team-beta\"
  }" | jq .
```

预期：返回 `success: false`，错误信息包含 `Namespace mismatch`。

### Step 4: 缺少 Namespace 参数

```bash
# 不传 namespace 参数
curl -s -X POST http://localhost:8080/api/rpc/command \
  -H "Content-Type: application/json" \
  -d '{"id":"ns-3","type":"get_state","sessionId":"any-session"}' | jq .
```

预期：返回 `success: false`，错误信息包含 `namespace is required`。

### Step 5: Namespace 级别的 Skill 覆盖

如果 namespace 私有 skill 与 public skill 同名，私有版本优先：

```bash
# 创建一个与公共 code-review 同名的 team-alpha 私有版本
mkdir -p skills/namespaces/team-alpha/code-review
cat > skills/namespaces/team-alpha/code-review/SKILL.md << 'EOF'
# Code Review (Alpha Team Custom)
Review code with Alpha Team's specific coding standards.
Focus on React component patterns and hooks best practices.
EOF

curl -s -X POST http://localhost:8080/api/rpc/catalog/reload
curl -s 'http://localhost:8080/api/rpc/catalog/skills?namespace=team-alpha' | \
  jq '.[] | select(.name=="code-review") | .description'
# → "Code Review (Alpha Team Custom)"

curl -s 'http://localhost:8080/api/rpc/catalog/skills?namespace=team-beta' | \
  jq '.[] | select(.name=="code-review") | .description'
# → "Code Review" (仍然是公共版本)
```

## 可见性矩阵

| Skill | `team-alpha` | `team-beta` | `unknown` | 来源 |
|-------|:---:|:---:|:---:|------|
| code-review | o (覆盖版) | o | o | public (可被 namespace 覆盖) |
| task-planning | o | o | o | public |
| lint-frontend | o | x | x | team-alpha 私有 |
| build-react | o | x | x | team-alpha 私有 |
| db-migrate | x | o | x | team-beta 私有 |
| api-test | x | o | x | team-beta 私有 |