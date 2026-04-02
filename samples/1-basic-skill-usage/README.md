# 案例 1: 基础 Skill 使用

演示 Skill 的两种模式：**指令型**（Instruction Skill）和**可执行型**（Executable Skill）。

## 目录结构

```
skills/
├── public/
│   └── code-review/
│       └── SKILL.md           ← 指令型 Skill（无 entrypoint）
└── namespaces/
    └── demo/
        └── greeting/
            ├── SKILL.md       ← 可执行型 Skill（有 entrypoint）
            └── greet.sh
```

## 前置条件

```bash
# 启动服务
cd delphi-agent
mvn spring-boot:run -pl delphi-coding-agent-server
```

## 测试步骤

### Step 1: 准备 Skill 文件

```bash
./setup-skills.sh
```

### Step 2: 重载 Catalog

```bash
curl -s -X POST http://localhost:8080/api/rpc/catalog/reload | jq .
```

预期输出：
```json
{
  "skills": 2,
  "prompts": 0,
  "resources": 0
}
```

### Step 3: 查看已注册的 Skills

```bash
# 查看 demo namespace 的可见 skills
curl -s 'http://localhost:8080/api/rpc/catalog/skills?namespace=demo' | jq .
```

预期输出：
```json
[
  {
    "name": "code-review",
    "description": "Code Review",
    "entrypoint": null,
    "argsSchema": null
  },
  {
    "name": "greeting",
    "description": "Greeting",
    "entrypoint": "./greet.sh",
    "argsSchema": "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"
  }
]
```

### Step 4: 通过 Chat 调用 Skill

指令型 Skill（LLM 读取 SKILL.md 内容作为指令）：

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "demo",
    "prompt": "请对以下代码进行 review: function add(a,b) { return a + b; }",
    "provider": "openai",
    "modelId": "gpt-4o-mini"
  }'
```

可执行型 Skill（通过沙箱执行脚本）：

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "demo",
    "prompt": "请向 Alice 打个招呼",
    "provider": "openai",
    "modelId": "gpt-4o-mini"
  }'
```

## 核心概念

| 类型 | 有 entrypoint | 执行方式 | 适用场景 |
|------|:---:|------|------|
| 指令型 | 否 | SKILL.md 内容注入 LLM 上下文 | 工作流、检查清单、编码规范 |
| 可执行型 | 是 | 通过 ExecutionBackend 沙箱执行 | 部署、构建、数据处理 |
