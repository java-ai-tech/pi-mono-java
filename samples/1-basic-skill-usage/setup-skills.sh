#!/bin/bash
# 准备 Skill 文件
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SKILLS_ROOT="$PROJECT_ROOT/skills"

echo "=== 创建 Skill 目录结构 ==="

# 指令型 Skill: code-review（公共）
mkdir -p "$SKILLS_ROOT/public/code-review"
cat > "$SKILLS_ROOT/public/code-review/SKILL.md" << 'EOF'
# Code Review
Review code for security vulnerabilities and best practices.

## 审查要点
1. **安全性** — 检查注入漏洞、XSS、敏感数据泄露
2. **错误处理** — 是否正确处理异常和边界情况
3. **性能** — 是否存在 N+1 查询、内存泄露等问题
4. **可读性** — 命名是否清晰、逻辑是否易于理解

## 输出格式
- 每个问题标注严重级别: [HIGH] [MEDIUM] [LOW]
- 给出具体的修复建议和代码示例
EOF

echo "  ✓ public/code-review/SKILL.md"

# 可执行 Skill: greeting（demo namespace 私有）
mkdir -p "$SKILLS_ROOT/namespaces/demo/greeting"
cat > "$SKILLS_ROOT/namespaces/demo/greeting/SKILL.md" << 'EOF'
---
entrypoint: "./greet.sh"
args_schema: '{"type":"object","properties":{"name":{"type":"string"}}}'
---
# Greeting
Generates a personalized greeting message for the given name.
EOF

cat > "$SKILLS_ROOT/namespaces/demo/greeting/greet.sh" << 'SCRIPT'
#!/bin/bash
NAME="${1:-World}"
echo "Hello, ${NAME}! Welcome to Pi Agent Framework."
echo "Current time: $(date '+%Y-%m-%d %H:%M:%S')"
SCRIPT
chmod +x "$SKILLS_ROOT/namespaces/demo/greeting/greet.sh"

echo "  ✓ namespaces/demo/greeting/SKILL.md + greet.sh"
echo ""
echo "=== Skills 准备完成 ==="
echo "下一步: curl -X POST http://localhost:8080/api/rpc/catalog/reload"