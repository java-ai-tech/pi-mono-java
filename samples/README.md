# 示例案例

本目录包含展示 Pi Agent Framework 核心功能的示例案例。

## 案例列表

| # | 目录 | 功能点 | 说明 |
|---|------|--------|------|
| 1 | `1-basic-skill-usage` | Skills | 指令型 / 可执行型 Skill 的注册与调用 |
| 2 | `2-sandbox-isolation` | Sandbox | Docker 容器隔离、网络/文件系统/资源限制、路径穿越防护 |
| 3 | `3-multi-namespace` | 多 Namespace | Skill 可见性矩阵、跨租户 Session 拒绝、Namespace 参数校验 |
| 4 | `4-multi-user-scenario` | 多用户 | 同 Namespace 下多用户独立 Session、共享 Skill、独立工作空间 |
| 5 | `5-comprehensive-demo` | 综合 | 端到端 SaaS 场景：多企业客户 + 多用户 + Skills 定制 + Sandbox 隔离 |

## 前置条件

在运行示例之前，请确保已安装以下工具：

- **Java 17+** 和 **Maven 3.6+**
- **Docker**（用于沙箱隔离，案例 2、5 需要）
- **curl**（发送 HTTP 请求）
- **jq**（解析 JSON 响应）

可选：
- 如果使用 `local-dev` profile，可以不需要 Docker

## 快速开始

```bash
# 启动服务
cd delphi-agent
mvn spring-boot:run -pl delphi-coding-agent-server

# 运行单个案例
bash samples/1-basic-skill-usage/test.sh

# 运行综合演示（覆盖所有功能）
bash samples/5-comprehensive-demo/test.sh

# 运行全部案例
for t in samples/*/test.sh; do echo "=== $t ==="; bash "$t"; done
```

## 目录结构

```
samples/
├── README.md                          ← 本文件
├── 1-basic-skill-usage/
│   ├── README.md                      ← 案例说明
│   ├── setup-skills.sh                ← 准备 Skill 文件
│   └── test.sh                        ← 自动化测试
├── 2-sandbox-isolation/
│   ├── README.md
│   └── test.sh
├── 3-multi-namespace/
│   ├── README.md
│   ├── setup-namespaces.sh
│   └── test.sh
├── 4-multi-user-scenario/
│   ├── README.md
│   └── test.sh
└── 5-comprehensive-demo/
    ├── README.md
    ├── setup-comprehensive.sh
    └── test.sh
```

## 功能覆盖矩阵

| 功能 | 案例1 | 案例2 | 案例3 | 案例4 | 案例5 |
|------|:---:|:---:|:---:|:---:|:---:|
| Skill 注册/加载 | **o** | | o | | **o** |
| 指令型 Skill | **o** | | | | o |
| 可执行型 Skill | **o** | | o | | o |
| Skill namespace 隔离 | | | **o** | | **o** |
| Skill 同名覆盖 | | | **o** | | **o** |
| Docker 沙箱执行 | | **o** | | | o |
| 网络隔离 | | **o** | | | |
| 只读文件系统 | | **o** | | | |
| 资源限制 | | **o** | | | |
| 路径穿越防护 | | **o** | | | **o** |
| 超时保护 | | **o** | | | |
| 多 Namespace | | | **o** | | **o** |
| 跨租户拒绝 | | | **o** | | **o** |
| Namespace 参数校验 | | | **o** | | **o** |
| 多用户 Session | | | | **o** | **o** |
| 工作空间隔离 | | | | **o** | **o** |
| 独立模型设置 | | | | **o** | |
| 并发执行 | | | | **o** | |